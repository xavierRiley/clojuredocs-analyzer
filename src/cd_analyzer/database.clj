(ns cd-analyzer.database
  (:use [cd-analyzer.util] 
	[cd-analyzer.language]
	[clojure.pprint :only (pprint)])
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.io File]))


;; From autodoc
(defn remove-leading-whitespace 
  "Find out what the minimum leading whitespace is for a doc block and remove it.
We do this because lots of people indent their doc blocks to the indentation of the 
string, which looks nasty when you display it."
  [s]
  (when s
    (let [lines (.split s "\\n") 
          prefix-lens (map #(count (re-find #"^ *" %)) 
                           (filter #(not (= 0 (count %))) 
                                   (next lines)))
          min-prefix (when (seq prefix-lens) (apply min prefix-lens))
          regex (when min-prefix (apply str "^" (repeat min-prefix " ")))]
      (if regex
        (apply str (interpose "\n" (map #(.replaceAll % regex "") lines)))
        s))))


;; TODO: where'd I get this?!
;;==== Internal functions ======================================================

(defn- join 
  "Joins the items in the given collection into a single string separated
   with the string separator."
  [separator col]
  (apply str (interpose separator col)))

(defn- sql-for-insert 
  "Converts a table identifier (keyword or string) and a hash identifying
   a record into an sql insert statement compatible with prepareStatement
    Returns [sql values-to-insert]"
  [table record]
  (let [table-name (name table)
        columns (map name (keys record))
        values (vals record)
        n (count columns)
        template (join "," (replicate n "?"))
        column-names (join "," columns)
        sql (format "insert into %s (%s) values (%s)"
                    table-name column-names template)]
    [sql values]))


;;==== Functions/macros for use by macros ======================================

(defn run-chained 
  "Runs the given database insert functions on the given
   database spec within a transaction. Each function is passed a hash
   identifying the keys of the previous inserts."
  [db insert-fns]
  (jdbc/with-connection db
    (jdbc/transaction
      (loop [id {}
             todo insert-fns]
        (if (empty? todo)
          id
          (let [[table insert-fn] (first todo)
                inserted-id (insert-fn id)]
            (recur (assoc id table inserted-id)
                   (rest todo))))))))

(defmacro build-insert-fns 
  "Converts a vector of [:table { record }] into a vector of database
   insert functions."
  [table-records]
  (vec
   (for [[table record] (partition 2 table-records)]
     `[~table (fn [~'id]
                (insert-record ~table ~record))])))


;;==== Functions/macros for external use =======================================

(defmacro insert-with-id 
  "Insert records within a single transaction into the database described by 
   the given db spec. The record format is :table  { record-hash }. 
   The record hashes can optionally access a hashmap 'id' which holds the
   autogenerated ids of previous inserts keyed by the table name. e.g.
    
      (insert-with-id db-spec
          :department {:name \"xfiles\"
                       :location \"secret\"}
          :employee   {:department (id :department)
                       :name \"Mr X\"})"
  [db & table-records]
  `(let [insert-fns# (build-insert-fns ~table-records)]
     (run-chained ~db insert-fns#)))

(def ^{:dynamic true} *db* {:classname "com.mysql.jdbc.Driver"
                            :subprotocol "mysql"
                            :subname "//localhost:3306/clojuredocs_development?user=root&password="
                            :create true
                            :username "root"
                            :password ""})

(defn query-lib-stats [libdef]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (let [lib-stats         (jdbc/with-query-results rs ["select * from libraries where name = ?" (:name libdef)]
                               (first rs))]
       (when lib-stats (assoc lib-stats 
			 :var-count -1))))))

(defn insert-or-update 
  [test-fn update-fn insert-fn]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (let [existing (test-fn)]
       (if existing (update-fn existing) (insert-fn))))))

(defn sql-now [] (java.sql.Timestamp. (System/currentTimeMillis)))

(defn url-friendly [s]
  (-> s
      str/lower-case
      (str/replace #"\s" "_")))

(defn store-lib [libdef]
  (let [name (:name libdef)
        version (:version libdef)
	description (:description libdef)
	site-url (:site-url libdef)
	source-base-url (:web-src-dir libdef)
	copyright (:copyright libdef)
	license (:license libdef)
	test (fn [] 
	       (jdbc/with-query-results rs ["select * from libraries where name = ? and version=? limit 1" name version] 
		 (first (doall rs))))
	update (fn [existing]
		 (jdbc/update-values :libraries
                                     ["id = ?" (:id existing)]
                                     {:description description
                                      :site_url site-url
                                      :source_base_url source-base-url
                                      :copyright copyright
                                      :license license
                                      :version version
                                      :updated_at (sql-now)
                                      :url_friendly_name (url-friendly name)}))
	insert (fn []
		 (jdbc/insert-values :libraries 
                                     [:name
                                      :description
                                      :site_url
                                      :source_base_url
                                      :copyright
                                      :license
                                      :version
                                      :url_friendly_name
                                      :created_at
                                      :updated_at]
                                     [name
                                      description
                                      site-url
                                      source-base-url
                                      copyright
                                      license
                                      version
                                      (url-friendly name)
                                      (sql-now)
                                      (sql-now)]))]
    (insert-or-update test update insert)))

(defn query-var [namespace-id name version]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (jdbc/with-query-results 
       rs 
       ["select * from functions where namespace_id=? and name=? and version=?" namespace-id name version]
       (first rs)))))

(defn query-ns [ns version]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (jdbc/with-query-results
       rs
       ["select * from namespaces where name=? and version=?" ns version]
       (first rs)))))

(defn query-library [name version]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (jdbc/with-query-results
       rs
       ["select * from libraries where name=? and version=?" name version]
       (first rs)))))

(defn store-var-map [library ns var-map]
  (try
    (let [{:keys [ns name file line arglists added doc source]} var-map
          version (:version library)]
      (jdbc/with-connection *db*
        (jdbc/transaction
         (let [namespace (query-ns ns version)
               existing (query-var (:id namespace) name version)]
           (if namespace
             (if existing
               (jdbc/update-values 
                :functions 
                ["id=?" (:id existing)] 
                {:namespace_id (:id namespace)
                 :version version
                 :name (str name)
                 :file file 
                 :line line 
                 :arglists_comp (apply str (interpose "|" arglists))
                 :added added
                 :doc doc
                 :shortdoc (if (:shortdoc existing) (:shortdoc existing) (apply str (take 70 doc)))
                 :source source
                 :updated_at (java.sql.Timestamp. (System/currentTimeMillis))
                 :url_friendly_name (url-friendly name)})
               (jdbc/insert-values
                :functions
                [:namespace_id :version :name :file :line :arglists_comp :added :doc :shortdoc :source :updated_at :created_at :url_friendly_name]
                [(:id namespace)
                 version
                 (str name) 
                 file 
                 line 
                 (apply str (interpose "|" arglists))
                 added
                 doc
                 (apply str (take 70 doc))
                 source
                 (java.sql.Timestamp. (System/currentTimeMillis))
                 (java.sql.Timestamp. (System/currentTimeMillis))
                 (url-friendly name)]))
             (println "Namespace" ns "not found, skipping insert of " name))))))
    (catch Exception e (println (str (:name var-map) " -- " e)))))

(defn lookup-var-id [var-map]
  (jdbc/transaction
   (jdbc/with-query-results rs ["select * from functions where ns = ? and name = ? limit 1" (str (:ns var-map)) (str (:name var-map))]
     (:id (first rs)))))

(defn remove-stale-vars [libname timestamp]
  #_(let [to-remove
          (jdbc/with-connection *db*
            (jdbc/transaction 
             (jdbc/with-query-results rs ["select * from functions where library = ? and (updated_at < ? or updated_at is NULL)" libname (java.sql.Timestamp. timestamp)]
               (doall rs))))
          ids (map :id to-remove)]
      (jdbc/with-connection *db*
        (jdbc/transaction
         (doseq [id ids]
           (jdbc/delete-rows :functions ["id=?" id]))))
      (jdbc/with-connection *db*
        (jdbc/transaction
         (doall (map #(jdbc/delete-rows :function_references ["from_function_id=?" %]) ids))
         (doall (map #(jdbc/delete-rows :function_references ["to_function_id=?" %]) ids))))
      (doall (seq to-remove))))

(defn store-ns-map [library ns-map]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (if-let [existing-lib (query-library (:name library) (:version library))]
       (let [lib-id (:id existing-lib)
             name (str (:name ns-map))
             web-path (:web-path ns-map)
             doc (:doc ns-map)
             doc (remove-leading-whitespace (if doc doc ""))
             existing (jdbc/with-query-results rs ["select * from namespaces where name = ? and version=? limit 1" name (:version library)] (first (doall rs)))]
         (if existing
           (jdbc/update-values :namespaces 
                               ["id=?" (:id existing)]
                               {:name name
                                :doc doc
                                :source_url web-path
                                :updated_at (sql-now)
                                :version (:version library)
                                :library_id (:id existing-lib)})
           (jdbc/insert-values :namespaces 
                               [:name
                                :doc
                                :source_url
                                :created_at
                                :updated_at
                                :version
                                :library_id] 
                               [name doc web-path
                                (sql-now)
                                (sql-now)
                                (:version library)
                                (:id existing-lib)])))
       (println "!! Couldn't find library in database" (:name library) (:version library))))))

(defn store-var-references [var-map]
  (when-let [vars-in (:vars-in var-map)]
    (try
      (jdbc/with-connection *db*
        (jdbc/transaction
         (when-let [from-id (lookup-var-id var-map)]
           (let [to-ids (map lookup-var-id vars-in)]
             (doseq [to-id to-ids]
               (when (not (nil? to-id))
                 (let [existing (jdbc/with-query-results rs 
                                  ["select * from function_references where from_function_id = ? and to_function_id = ? limit 1" from-id to-id] 
                                  (first (doall rs)))]
                   (when (not existing)
                     (jdbc/insert-records :function_references {:from_function_id from-id :to_function_id to-id})))))
             true))))
      (catch Exception e 
        (reportln "Exception in store-var-references: ") 
        (reportln var-map " -> " (.getMessage e)) 
        nil)))

  (def ccld {:name "Clojure Core"
             :root-dir "/Users/zkim/clojurelibs/clojure"
             :src-dir "/Users/zkim/clojurelibs/clojure/src"
             :description "Clojure core environment and runtime library."
             :site-url "http://clojure.org"
             :source-base-url "http://github.com/clojure/clojure/blob/master/src/clj/"
             :copyright "&copy Rich Hickey.  All rights reserved."
             :license "<a href=\"http://www.eclipse.org/legal/epl-v10.html\">Eclipse Public License 1.0</a>"}))

(defn track-import-start [libdef]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (when-let [libid (jdbc/with-query-results rs ["select id from libraries where name=?", (:name libdef)]
			(:id (first rs)))]
       (jdbc/insert-record :library_import_tasks
		       {:library_id libid
			:status "RUNNING"
			:created_at (java.sql.Timestamp. (System/currentTimeMillis))
			:updated_at (java.sql.Timestamp. (System/currentTimeMillis))})))))

(defn track-import-end [task-id lib-stats]
  (when (contains? lib-stats :status)
    (jdbc/with-connection *db*
      (jdbc/transaction
       (jdbc/update-values :library_import_tasks
                           ["id = ?" task-id]
                           (assoc lib-stats
                             :updated_at (java.sql.Timestamp. (System/currentTimeMillis))))))))

(defn import-log [task-id level message]
  (jdbc/with-connection *db*
    (jdbc/transaction
     (jdbc/insert-record :library_import_logs
		    {:library_import_task_id task-id
		     :level (name level)
		     :message message
		     :created_at (java.sql.Timestamp. (System/currentTimeMillis))}))))


