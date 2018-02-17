(ns rebel-readline-cljs.service
  (:require
   [cljs-tooling.complete :as cljs-complete]
   [cljs-tooling.info :as cljs-info]
   [cljs.analyzer :as ana]
   [cljs.analyzer.api :as ana-api]
   [cljs.core]
   [cljs.env]
   [cljs.repl]
   [cljs.tagged-literals :as tags]
   [clojure.string :as string]
   [clojure.tools.reader :as reader]
   [clojure.tools.reader.reader-types :as readers]
   [rebel-readline.info.doc-url :as doc-url]
   [rebel-readline.service :as srv]
   [rebel-readline.service.local-clojure :refer [call-with-timeout]]
   [rebel-readline.tools.colors :as colors]
   [rebel-readline.utils :refer [log]])
  (:import
   [java.util.regex Pattern]))

(defn format-document [{:keys [ns name type arglists doc]}]
  (when doc
    (string/join
     (System/getProperty "line.separator")
     (cond-> []
       (and ns name) (conj (str ns "/" name))
       type (conj (name type))
       arglists (conj (pr-str arglists))
       doc (conj (str "  " doc))))))

;; taken from cljs.repl
(defn- named-publics-vars
  "Gets the public vars in a namespace that are not anonymous."
  [ns]
  (->> (ana-api/ns-publics ns)
       (remove (comp :anonymous val))
       (map key)))

;; taken from cljs.repl and translated into a fn
(defn apropos
  "Given a regular expression or stringable thing, return a seq of all
  public definitions in all currently-loaded namespaces that match the
  str-or-pattern."
  [str-or-pattern]
  (let [matches? (if (instance? Pattern str-or-pattern)
                   #(re-find str-or-pattern (str %))
                   #(.contains (str %) (str str-or-pattern)))]
    (sort
     (mapcat
      (fn [ns]
        (let [ns-name (str ns)]
          (map #(symbol ns-name (str %))
               (filter matches? (named-publics-vars ns)))))
      (ana-api/all-ns)))))

(defn read-cljs-string [form-str]
  (when-not (string/blank? form-str)
    (try
      {:form (binding [*ns* (create-ns ana/*cljs-ns*)
                       reader/resolve-symbol ana/resolve-symbol
                       reader/*data-readers* tags/*cljs-data-readers*
                       reader/*alias-map*
                       (apply merge
                              ((juxt :requires :require-macros)
                               (ana/get-namespace ana/*cljs-ns*)))]
                 (reader/read {:read-cond :allow :features #{:cljs}}
                              (readers/source-logging-push-back-reader
                               (java.io.StringReader. form-str))))}
      (catch Exception e
        {:exception (Throwable->map e)}))))

(defn eval-cljs [repl-env env form]
  (let [res (cljs.repl/evaluate-form repl-env
                                     (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                                     "<cljs repl>"
                                     form
                                     (#'cljs.repl/wrap-fn form))]
    res))

(defn data-eval
  [eval-thunk]
  (let [out-writer (java.io.StringWriter.)
        err-writer (java.io.StringWriter.)
        capture-streams (fn []
                          (.flush *out*)
                          (.flush *err*)
                          {:out (.toString out-writer)
                           :err (.toString err-writer)})]
    (binding [*out* (java.io.BufferedWriter. out-writer)
              *err* (java.io.BufferedWriter. err-writer)]
      (try
        (let [result (eval-thunk)]
          (Thread/sleep 100) ;; give printed data time to propagate
          (merge (capture-streams) {:printed-result result}))
        (catch Throwable t
          (merge (capture-streams) {:exception (Throwable->map t)}))))))

(defmethod srv/-current-ns ::service [_] (some-> ana/*cljs-ns* str))

(defmethod srv/-complete ::service [_ word {:keys [ns]}]
  (let [options (cond-> nil
                  ns (assoc :current-ns ns))]
    (cljs-complete/completions @cljs.env/*compiler* word options)))

(defmethod srv/-resolve-meta ::service [self var-str]
  (cljs-info/info @cljs.env/*compiler* var-str
                  (srv/-current-ns self)))

(defmethod srv/-doc ::service [self var-str]
  (when-let [{:keys [ns name] :as info} (srv/-resolve-meta self var-str)]
    (when-let [doc (format-document info)]
      (let [url (doc-url/url-for (str ns) (str name))]
        (cond-> {:doc doc}
          url (assoc :url url))))))

(defmethod srv/-source ::service [_ var-str]
  (some->> (cljs.repl/source-fn @cljs.env/*compiler* (symbol var-str))
           (hash-map :source)))

(defmethod srv/-apropos ::service [_ var-str] (apropos var-str))

(defmethod srv/-read-string ::service [_ form-str] (read-cljs-string form-str))

(defmethod srv/-eval ::service [self form]
  (when-let [repl-env (:repl-env self)]
    (call-with-timeout
     (fn []
       (data-eval #(eval-cljs repl-env (ana/empty-env) form)))
     (get self :eval-timeout 3000))))

(defmethod srv/-eval-str ::service [self form-str]
  (let [res (srv/-read-string self form-str)]
    (if (contains? res :form)
      (let [form (:form res)]
        (srv/-eval self form))
      res)))

;; this needs a :repl-eval option

(defn create
  ([] (create nil))
  ([options]
   (atom (merge srv/default-config options {::srv/type ::service}))))
