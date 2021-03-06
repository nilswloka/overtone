(ns
  ^{:doc "An interface to the SuperCollider synthesis server.
          This is at heart an OSC client library for the SuperCollider
          scsynth DSP engine."
     :author "Jeff Rose"}
  overtone.sc.core
  (:import
    (java.net InetSocketAddress)
    (java.util.regex Pattern)
    (java.util.concurrent TimeUnit TimeoutException)
    (java.io BufferedInputStream)
    (supercollider ScSynth ScSynthStartedListener MessageReceivedListener)
    (java.util BitSet))
  (:require [overtone.log :as log])
  (:use
    [overtone event config setup util time-utils]
    [overtone.sc synthdef boot]
    [clojure.contrib.java-utils :only [file]]
    [clojure.contrib pprint]
    osc))

; TODO: Make this work correctly
; NOTE: "localhost" doesn't work, at least on my laptopt
(defonce SERVER-HOST "127.0.0.1")
(defonce SERVER-PORT nil) ; nil means a random port

; Max number of milliseconds to wait for a reply from the server
(defonce REPLY-TIMEOUT 500)

; Server limits
(defonce MAX-NODES 1024)
(defonce MAX-BUFFERS 1024)
(defonce MAX-SDEFS 1024)
(defonce MAX-AUDIO-BUS 128)
(defonce MAX-CONTROL-BUS 4096)
(defonce MAX-OSC-SAMPLES 8192)

; We use bit sets to store the allocation state of resources on the audio server.
; These typically get allocated on usage by the client, and then freed either by
; client request or automatically by receiving notifications from the server.
; (e.g. When an envelope trigger fires to free a synth.)
(defonce allocator-bits
  {:node         (BitSet. MAX-NODES)
   :audio-buffer (BitSet. MAX-BUFFERS)
   :audio-bus    (BitSet. MAX-NODES)
   :control-bus  (BitSet. MAX-NODES)})

(defonce allocator-limits
  {:node        MAX-NODES
   :sdefs       MAX-SDEFS
   :audio-bus   MAX-AUDIO-BUS
   :control-bus MAX-CONTROL-BUS})

(defn alloc-id
  "Allocate a new ID for the type corresponding to key."
  [k]
  (let [bits  (get allocator-bits k)
        limit (get allocator-limits k)]
    (locking bits
      (let [id (.nextClearBit bits 0)]
        (if (= limit id)
          (throw (Exception. (str "No more " (name k) " ids available!")))
          (do
            (.set bits id)
            id))))))

; The root group is implicitly allocated
(defonce _root-group_ (alloc-id :node))

(defn free-id
  "Free the id of type key."
  [k id]
  (let [bits (get allocator-bits k)
        limit (get allocator-limits k)]
    (locking bits
      (.clear bits id))))

(defn all-ids
  "Get all of the currently allocated ids for key."
  [k]
  (let [bits (get allocator-bits k)
        limit (get allocator-limits k)]
    (locking bits
      (loop [ids []
             idx 0]
        (let [id (.nextSetBit bits idx)]
          (if (and (> id -1) (< idx limit))
            (recur (conj ids id) (inc id))
            ids))))))

(defn clear-ids
  "Clear all ids allocated for key."
  [k]
  (println "clear-ids........................")
  (locking (get allocator-bits k)
    (doseq [id (all-ids k)]
      (free-id k id))))

(defonce ROOT-GROUP 0)
(defonce synth-group* (ref nil))

(declare group)
(on-sync-event :connected ::root-group-creator
  #(dosync (ref-set synth-group* (group :head ROOT-GROUP))))

(declare boot)

; The base handler for receiving osc messages just forwards the message on
; as an event using the osc path as the event key.
(on-sync-event :osc-msg-received ::osc-receiver
               (fn [{{path :path args :args} :msg}]
                 (event path :path path :args args)))

(def osc-log* (atom []))
(on-sync-event :osc-msg-received ::osc-logger
               (fn [{:keys [path args] :as msg}]
                 (swap! osc-log* #(conj % msg))))
(defmacro at
  "All messages sent within the body will be sent in the same timestamped OSC
  bundle.  This bundling is thread-local, so you don't have to worry about
  accidentally scheduling packets into a bundle started on another thread."
  [time-ms & body]
  `(in-osc-bundle @server* ~time-ms (do ~@body)))

(defn connected? []
  (= :connected @status*))

(defn snd
  "Sends an OSC message."
  [path & args]
  (log/debug "(snd " path args ")")
  (if (connected?)
    (apply osc-send @server* path args)
    (log/debug "### trying to snd while disconnected! ###")))

(defn debug
  "Control debug output from both the Overtone and the audio server."
  [& [on-off]]
  (if (or on-off (nil? on-off))
    (do
      (log/level :debug)
      (osc-debug true)
      (snd "/dumpOSC" 1))
    (do
      (log/level :error)
      (osc-debug false)
      (snd "/dumpOSC" 0))))

; Trigger Notifications
;
; This command is the mechanism that synths can use to trigger events in
; clients.  The node ID is the node that is sending the trigger. The trigger ID
; and value are determined by inputs to the SendTrig unit generator which is
; the originator of this message.
;
; /tr a trigger message
;
;   int - node ID
;   int - trigger ID
;   float - trigger value
(defonce trigger-handlers* (ref {}))

(defn on-trigger [node-id trig-id f]
  (dosync (alter trigger-handlers* assoc [node-id trig-id] f)))

(defn remove-trigger [node-id trig-id]
  (dosync (alter trigger-handlers* dissoc [node-id trig-id])))

(on-event "/tr" ::trig-handler
  (fn [msg]
    (let [[node-id trig-id value] (:args msg)
          handler (get @trigger-handlers* [node-id trig-id])]
      (if handler
        (handler node-id trig-id value)))))

(defn- node-destroyed
  "Frees up a synth node to keep in sync with the server."
  [id]
  (log/debug (format "node-destroyed: %d" id))
  (free-id :node id))

(defn- node-created
  "Called when a node is created on the synth."
  [id]
  (log/debug (format "node-created: %d" id)))

; Setup the feedback handlers with the audio server.
(on-event "/n_end" ::node-destroyer #(node-destroyed (first (:args %))))
(on-event "/n_go" ::node-creator #(node-created (first (:args %))))

(declare reset)

; TODO: setup an error-handler in the case that we can't connect to the server
(defn connect
  "Connect to an external SC audio server on the specified host and port."
  [& [host port]]
   (if (and host port)
     (.run (Thread. #(connect-external host port)))
     (connect-internal)))

(on-event :connect ::connect-handler
  (fn [event]
    (if (and (contains? event :host) (contains? event :port))
      (connect (:host event) (:port event))
      (connect))))

(defn server-log
  "Print the server log."
  []
  (doseq [msg @server-log*]
    (print msg)))

(defn recv
  "Register your intent to wait for a message associated with given path to be received from the server. Returns a promise that will contain the message once it has been received. Does not block current thread (this only happens once you try and look inside the promise and the reply has not yet been received)."
  [path]
  (let [p (promise)]
    (on-sync-event path (uuid) #(do (deliver p %) :done))
    p))

(defn await-promise
    "Read the reply received from the server, waiting for timeout ms if the message hasn't yet been received. Returns :timeout if a timeout occurs."
    ([prom] (await-promise prom REPLY-TIMEOUT))
    ([prom timeout]
       (try
         (.get (future @prom) timeout TimeUnit/MILLISECONDS)
         (catch TimeoutException t
           :timeout))))

(defn await-promise!
    "Read the reply received from the server, waiting for timeout ms if the message hasn't yet been received. Raises an exception if the message hasn't been received within timeout ms"
    ([prom] (await-promise prom REPLY-TIMEOUT))
    ([prom timeout]
       (.get (future @prom) timeout TimeUnit/MILLISECONDS)))

(defn- parse-status [args]
  (let [[_ ugens synths groups loaded avg peak nominal actual] args]
    {:n-ugens ugens
     :n-synths synths
     :n-groups groups
     :n-loaded-synths loaded
     :avg-cpu avg
     :peak-cpu peak
     :nominal-sample-rate nominal
     :actual-sample-rate actual}))

(def STATUS-TIMEOUT 500)

;Replies to sender with the following message.
;status.reply
;	int - 1. unused.
;	int - number of unit generators.
;	int - number of synths.
;	int - number of groups.
;	int - number of loaded synth definitions.
;	float - average percent CPU usage for signal processing
;	float - peak percent CPU usage for signal processing
;	double - nominal sample rate
;	double - actual sample rate
(defn status
  "Check the status of the audio server."
  []
  (if (= :connected @status*)
    (let [p (promise)
          handler (fn [event]
                   (deliver p (parse-status (:args event)))
                   (remove-handler "status.reply" ::status-check)
                   (remove-handler "/status.reply" ::status-check))]
      (on-event "/status.reply" ::status-check handler)
      (on-event "status.reply" ::status-check handler)

      (snd "/status")
      (try
        (.get (future @p) STATUS-TIMEOUT TimeUnit/MILLISECONDS)
        (catch TimeoutException t
          :timeout)))
    @status*))

(defn wait-sync
  "Wait until the audio server has completed all asynchronous commands currently in execution."
  [& [timeout]]
  (let [sync-id (rand-int 999999)
        reply-p (recv "/synced")
        _ (snd "/sync" sync-id)
        reply (await-promise! reply-p (if timeout timeout REPLY-TIMEOUT))
        reply-id (first (:args reply))]
    (= sync-id reply-id)))

(defn boot
  "Boot either the internal or external audio server."
  ([] (boot (get @config* :server :internal) SERVER-HOST SERVER-PORT))
  ([which & [port]]
   (let [port (if (nil? port) (+ (rand-int 50000) 2000) port)]
     (cond
       (= :internal which) (boot-internal port)
       (= :external which) (boot-external port)))))

(defn quit
  "Quit the SuperCollider synth process."
  []
  (log/info "quiting supercollider")
  (event :quit)
  (reset! running?* false)
  (dosync (ref-set server* nil)
    (ref-set status* :no-audio))
  (when (connected?)
    (snd "/quit")
    (log/debug "SERVER: " @server*)
    (osc-close @server* true)))

; TODO: Come up with a better way to delay shutdown until all of the :quit event handlers
; have executed.  For now we just use 500ms.
(defonce _shutdown-hook (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (quit) (Thread/sleep 500)))))

; Synths, Busses, Controls and Groups are all Nodes.  Groups are linked lists
; and group zero is the root of the graph.  Nodes can be added to a group in
; one of these 5 positions relative to either the full list, or a specified node.
(def POSITION
  {:head         0
   :tail         1
   :before-node  2
   :after-node   3
   :replace-node 4})

;; Sending a synth-id of -1 lets the server choose an ID
(defn node
  "Instantiate a synth node on the audio server.  Takes the synth name and a set of
  argument name/value pairs.  Optionally use :target <node/group-id> and :position <pos>
  to specify where the node should be located.  The position can be one of :head, :tail
  :before-node, :after-node, or :replace-node.

  (node \"foo\")
  (node \"foo\" :pitch 60)
  (node \"foo\" :pitch 60 :target 0)
  (node \"foo\" :pitch 60 :target 2 :position :tail)
  "
  [synth-name & args]
  (if (not (connected?))
    (throw (Exception. "Not connected to synthesis engine.  Please boot or connect.")))
  (let [id (alloc-id :node)
        argmap (apply hash-map args)
        position (or ((get argmap :position :tail) POSITION) 1)
        target (get argmap :target 0)
        args (flatten (seq (-> argmap (dissoc :position) (dissoc :target))))
        args (stringify (floatify args))]
    ;(println "node " synth-name id position target args)
    (apply snd "/s_new" synth-name id position target args)
    id))

(defn node-free
  "Remove a synth node."
  [& node-ids]
  {:pre [(connected?)]}
  (apply snd "/n_free" node-ids)
  (doseq [id node-ids] (free-id :node id)))

(defn group
  "Create a new synth group as a child of the target group."
  [position target-id]
  {:pre [(connected?)]}
  (let [id (alloc-id :node)
        pos (if (keyword? position) (get POSITION position) position)
        pos (or pos 1)]
    (snd "/g_new" id pos target-id)
    id))

(defn group-free
  "Free synth groups, releasing their resources."
  [& group-ids]
  {:pre [(connected?)]}
  (apply node-free group-ids))

(defn node-run
  "Start a stopped synth node."
  [node-id]
  {:pre [(connected?)]}
  (snd "/n_run" node-id 1))

(defn node-stop
  "Stop a running synth node."
  {:pre [(connected?)]}
  [node-id]
  (snd "/n_run" node-id 0))

(defn node-place
  "Place a node :before or :after another node."
  [node-id position target-id]
  {:pre [(connected?)]}
  (cond
    (= :before position) (snd "/n_before" node-id target-id)
    (= :after  position) (snd "/n_after" node-id target-id)))

(defn node-control
  "Set control values for a node."
  [node-id & name-values]
  {:pre [(connected?)]}
  (apply snd "/n_set" node-id (floatify (stringify name-values)))
  node-id)

; This can be extended to support setting multiple ranges at once if necessary...
(defn node-control-range
  "Set a range of controls all at once, or if node-id is a group control
  all nodes in the group."
  [node-id ctl-start & ctl-vals]
  {:pre [(connected?)]}
  (apply snd "/n_setn" node-id ctl-start (count ctl-vals) ctl-vals))

(defn node-map-controls
  "Connect a node's controls to a control bus."
  [node-id & names-busses]
  {:pre [(connected?)]}
  (apply snd "/n_map" node-id names-busses))

(defn post-tree
  "Posts a representation of this group's node subtree, i.e. all the groups and
  synths contained within it, optionally including the current control values
  for synths."
  [id & [with-args?]]
  {:pre [(connected?)]}
  (snd "/g_dumpTree" id with-args?))

(defn node-tree
  "Returns a data structure representing the current arrangement of groups and
  synthesizer instances residing on the audio server."
  ([] (node-tree 0))
  ([id & [ctls?]]
   (let [ctls? (if (or (= 1 ctls?) (= true ctls?)) 1 0)]
     (let [reply-p (recv "/g_queryTree.reply")
           _ (snd "/g_queryTree" id ctls?)
          tree (:args (await-promise! reply-p))]
       (with-meta (parse-node-tree tree)
         {:type ::node-tree})))))

(defn prepend-node
  "Add a synth node to the end of a group list."
  [g n]
  (snd "/g_head" g n))

(defn append-node
  "Add a synth node to the end of a group list."
  [g n]
  (snd "/g_tail" g n))

(defn group-clear
  "Free all child synth nodes in a group."
  [group-id]
  (snd "/g_freeAll" group-id))

(defn clear-msg-queue
  "Remove any scheduled OSC messages from the run queue."
  []
  (snd "/clearSched"))

; The /done message just has a single argument:
; "/done" "s" <completed-command>
;
; where the command would be /b_alloc and others.
(defn on-done
  "Runs a one shot handler that takes no arguments when an OSC /done
  message from scsynth arrives with a matching path.  Look at load-sample
  for an example of usage.
  "
  [path handler]
  (on-event "/done" (uuid)
            #(if (= path (first (:args %)))
               (do
                 (handler)
                 :done))))

; TODO: Look into multi-channel buffers.  Probably requires adding multi-id allocation
; support to the bit allocator too...
; size is in samples
(defn buffer
  "Allocate a new buffer for storing audio data."
  [size & [channels]]
  (let [channels (or channels 1)
        id (alloc-id :audio-buffer)
        ready? (atom false)]
    (on-done "/b_alloc" #(reset! ready? true))
    (snd "/b_alloc" id size channels)
    (with-meta {:id id
                :size size
                :ready? ready?}
               {:type ::buffer})))

(defn ready?
  "Check whether a sample or a buffer has completed allocating and/or loading data."
  [buf]
  @(:ready? buf))

(defn buffer? [buf]
  (isa? (type buf) ::buffer))

(defn- buf-or-id [b]
  (cond
    (buffer? b) (:id b)
    (number? b) b
    :default (throw (Exception. "Not a valid buffer: " b))))

(defn buffer-free
  "Free an audio buffer and the memory it was consuming."
  [buf]
  (snd "/b_free" (:id buf))
  (free-id :audio-buffer (:id buf))
  :done)

; TODO: Test me...
(defn buffer-read
  "Read a section of an audio buffer."
  [buf start len]
  (assert (buffer? buf))
  (loop [reqd 0]
    (when (< reqd len)
      (let [to-req (min MAX-OSC-SAMPLES (- len reqd))]
        (snd "/b_getn" (:id buf) (+ start reqd) to-req)
        (recur (+ reqd to-req)))))
  (let [samples (float-array len)]
    (loop [recvd 0]
      (if (= recvd len)
        samples
        (let [msg-p (recv "/b_setn")
              msg (await-promise! msg-p)
              ;_ (println "b_setn msg: " (take 3 (:args msg)))
              [buf-id bstart blen & samps] (:args msg)]
          (loop [idx bstart
                 samps samps]
            (when samps
              (aset-float samples idx (first samps))
              (recur (inc idx) (next samps))))
          (recur (+ recvd blen)))))))

;; TODO: test me...
(defn buffer-write
  "Write into a section of an audio buffer."
  [buf start len data]
  (assert (buffer? buf))
  (snd "/b_setn" (:id buf) start len data))

(defn save-buffer
  "Save the float audio data in an audio buffer to a wav file."
  [buf path & args]
  (assert (buffer? buf))
  (let [arg-map (merge (apply hash-map args)
                       {:header "wav"
                        :samples "float"
                        :n-frames -1
                        :start-frame 0
                        :leave-open 0})
        {:keys [header samples n-frames start-frame leave-open]} arg-map]
    (snd "/b_write" (:id buf) path header samples
         n-frames start-frame
         (if leave-open 1 0))
    :done))

(defmulti buffer-id type)
(defmethod buffer-id java.lang.Integer [id] id)
(defmethod buffer-id ::buffer [buf] (:id buf))

(defn buffer-data
  "Get the floating point data for a buffer on the internal server."
  [buf]
  (let [buf-id (buffer-id buf)
        snd-buf (.getSndBufAsFloatArray @sc-world* buf-id)]
    snd-buf))

(defn buffer-info [buf]

  (let [mesg-p (recv "/b_info")
        _   (snd "/b_query" (buffer-id buf))
        msg (await-promise! mesg-p)
        [buf-id n-frames n-channels rate] (:args msg)]
    {:n-frames n-frames
     :n-channels n-channels
     :rate rate}))

(defn sample-info [s]
  (buffer-info (:buf s)))

(defonce loaded-synthdefs* (ref {}))

(defn load-synthdef
  "Load a Clojure synth definition onto the audio server."
  [sdef]
  (assert (synthdef? sdef))
  (dosync (alter loaded-synthdefs* assoc (:name sdef) sdef))
  (if (connected?)
    (snd "/d_recv" (synthdef-bytes sdef))))

(defn- load-all-synthdefs []
  (doseq [[sname sdef] @loaded-synthdefs*]
    (snd "/d_recv" (synthdef-bytes sdef))))

(on-event :connected ::synthdef-loader load-all-synthdefs)

(defn load-synth-file
  "Load a synth definition file onto the audio server."
  [path]
  (snd "/d_recv" (synthdef-bytes (synthdef-read path))))

; TODO: need to clear all the buffers and busses
;  * Think about a sane policy for setting up state, especially when we are connected
; with many peers on one or more servers...
(defn reset
  "Clear all live synthesizers and instruments, and remove any scheduled triggers or control messages."
  []
  (event :reset))

(defn stop
  "Stop all audio."
  []
  (reset))

(on-sync-event :reset :reset-base
  (fn []
    (clear-msg-queue)
    (group-clear @synth-group*))) ; clear the synth group

(defn restart
  "Reset everything and restart the SuperCollider process."
  []
  (reset)
  (quit)
  (boot))

(defmulti hit-at (fn [& args] (type (second args))))

(defmethod hit-at String [time-ms synth & args]
  (at time-ms (apply node synth args)))

(defmethod hit-at clojure.lang.Keyword [time-ms synth & args]
  (at time-ms (apply node (name synth) args)))

(defmethod hit-at ::sample [time-ms synth & args]
  (apply hit-at time-ms "granular" :buf (get-in synth [:buf :id]) args))

(defmethod hit-at :default [& args]
  (throw (Exception. (str "Hit doesn't know how to play the given synth type: " args))))

; Turn hit into a multimethod
; Convert samples to be a map object instead of an ID
(defn hit
  "Fire off a synth or sample at a specified time.
  These are the same:
  (hit :kick)
  (hit \"kick\")
  (hit (now) \"kick\")

  Or you can get fancier like this:
  (hit (now) :sin :pitch 60)
  (doseq [i (range 10)] (hit (+ (now) (* i 250)) :sin :pitch 60 :dur 0.1))

  "
  ([] (hit-at (now) "ping" :pitch (choose [60 65 72 77])))
  ([& args]
   (apply hit-at (if (isa? (type (first args)) Number)
                   args
                   (cons (now) args)))))

(defmacro check
  "Try out an anonymous synth definition.  Useful for experimentation.  If the
  root node is not an out ugen, then it will add one automatically."
  [body]
  `(do
     (load-synthdef (synth "audition-synth" {} ~body))
     (let [note# (hit (now) "audition-synth")]
       (at (+ (now) 1000) (node-free note#)))))


(defn- synth-kind
  "Resolve synth kind depending on type of arguments. Intended for use as a multimethod dispatch fn"
  [& args]
  (cond
   (number? (first args)) :number
   (associative? (first args)) (:type (first args))
   :else (type (first args))))

(defmulti ctl
  "Modify synth parameters, optionally at a specified time."
  synth-kind)

(defmethod ctl :number
  [synth-id & ctls]
  (apply node-control synth-id ctls))

(defmulti kill
  "Free one or more synth nodes.
  Functions that create instance of synth definitions, such as hit, return
  a handle for the synth node that was created.
  (let [handle (hit :sin)] ; returns => synth-handle
  (kill (+ 1000 (now)) handle))

  ; a single handle without a time kills immediately
  (kill handle)

  ; or a bunch of synth handles can be removed at once
  (kill (hit) (hit) (hit))

  ; or a seq of synth handles can be removed at once
  (kill [(hit) (hit) (hit)])
  "
  synth-kind)

(defmethod kill :number
  [& ids]
  (apply node-free (flatten ids))
  :killed)

(defn load-instruments []
  (doseq [synth (filter #(synthdef? %1)
                        (map #(var-get %1)
                             (vals (ns-publics 'overtone.instrument))))]
    ;(println "loading synth: " (:name synth))
    (load-synthdef synth)))

;(defn update
;  "Update a voice or standalone synth with new settings."
;  [voice & args]
;  (let [[names vals] (synth-args (apply hash-map args))
;        synth        (if (voice? voice) (:synth voice) voice)]
;    (.set synth names vals)))

;(defmethod play-note :synth [voice note-num dur & args]
;  (let [args (assoc (apply hash-map args) :note note-num)
;        synth (trigger (:synth voice) args)]
;    (schedule #(release synth) dur)
;    synth))

(defn- name-synth-args [args names]
  (loop [args args
         names names
         named []]
    (if args
      (recur (next args)
             (next names)
             (concat named [(first names) (first args)]))
      named)))

(defn synth-player
  "Returns a player function for a named synth.  Used by (synth ...) internally, but can be
  used to generate a player for a pre-compiled synth.  The function generated will accept two
  optional arguments that must come first, the :target and :position (see the node function docs).

  (foo)
  (foo :target 0 :position :tail)

  or if foo has two arguments:
  (foo 440 0.3)
  (foo :target 0 :position :tail 440 0.3)
  at the head of group 2:
  (foo :target 2 :position :head 440 0.3)

  These can also be abbreviated:
  (foo :tgt 2 :pos :head)
  "
  [sname arg-names]
  (fn [& args]
    (let [[args sgroup] (if (or (= :target (first args))
                                (= :tgt    (first args)))
                          [(drop 2 args) (second args)]
                          [args @synth-group*])
          [args pos]    (if (or (= :position (first args))
                                (= :pos      (first args)))
                          [(drop 2 args) (second args)]
                          [args :tail])
          controller    (partial node-control sgroup)
          player        (partial node sname :target sgroup :position pos)
          [tgt-fn args] (if (= :ctl (first args))
                          [controller (rest args)]
                          [player args])
          args (map #(if (buffer? %) (:id %) %) args)
          named-args (if (keyword? (first args))
                       args
                       (name-synth-args args arg-names))]
        (apply tgt-fn named-args))))

(defn booted?
  "Returns true or false depending on whether scsynth has booted"
  []
  @running?*)

(defn wait-until-booted
  "Makes the current thread sleep until scsynth has successfully booted"
  []
  (while (not (booted?))
    (Thread/sleep 100)))
