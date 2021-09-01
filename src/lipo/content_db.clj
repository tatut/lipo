(ns lipo.content-db
  "Queries and transactions for content pages."
  (:require [crux.api :as crux]
            [clojure.string :as str]))

(def ^:private root-path #{nil "" "/"})

(defn content-ref [content-id-or-entity]
  {:pre [(or
          (uuid? content-id-or-entity)
          (and (map? content-id-or-entity)
               (uuid? (:crux.db/id content-id-or-entity))))]}
  (if (uuid? content-id-or-entity)
    content-id-or-entity
    (:crux.db/id content-id-or-entity)))

(defn paths-with-children
  "Given a sequence of paths return a set of those paths that have children."
  [db paths]
  (into #{}
        (map first)
        (crux/q db '[:find ?parent ?c :where [?c :content/parent ?parent]
                     :in [?parent ...]] paths)))

(declare content-id)

(defn ls
  "List content under path.

  Options:
  :type   if specified (keyword), only lists content of the given
          type
  :check-children?
          if true (default false), check each returned item if they
          have children and include it as :content/has-children?
  "
  [db path-or-parent & {:keys [type check-children?]
                        :or {check-children? false}}]
  (let [parent (cond
                 (contains? root-path path-or-parent) nil
                 (uuid? path-or-parent) path-or-parent
                 :else (content-id db path-or-parent))
        parent-where (if-not parent
                       '(not-join [?c] [?c :content/parent])
                       '[?c :content/parent ?parent])
        type-where (when type
                     '[?c :content/type ?type])
        results
        (->>
         (crux/q db
                 {:find '[(pull ?c [:crux.db/id
                                    :content/path :content/title
                                    :content/parent :content/type])]
                  :where (filterv
                          some?
                          [parent-where
                           type-where
                           '[?c :content/title _]])
                  :in '[?parent ?type]}
                 parent type)
         (mapv first)
         (sort-by :content/title))]

    (if check-children?
      (let [children? (paths-with-children db (map :crux.db/id results))]
        (for [r results]
          (assoc r :content/has-children? (contains? children? (:crux.db/id r)))))
      results)))


(defn has-children?
  "Check if given path has children. Returns boolean."
  [db path]
  (some?
   (ffirst
    (crux/q db '[:find ?c :limit 1 :where [?c :content/parent ?parent]
                 :in ?parent] path))))



(defn parents-of
  "Return ids of all parents for given path. Not including root."
  [db content]
  (loop [parents (list)
         here content]
    (if-let [parent (:content/parent (crux/pull db [:content/parent] here))]
      (recur (cons parent parents) parent)
      (vec parents))))

(defn content-id
  "Resolve id for content.
  If passed an id, it is returned as is.
  If passed a path string, the content is queried.

  If passed the root path, returns nil."
  [db id-or-path]
  (cond
    (uuid? id-or-path)
    id-or-path

    (contains? root-path id-or-path)
    nil

    (and (string? id-or-path)
         (str/starts-with? id-or-path "/"))
    (let [[_ & components] (str/split id-or-path #"/")
          content-sym (zipmap components
                              (map #(symbol (str "c" %)) (range)))
          path-sym (zipmap components
                           (map #(symbol (str "p" %)) (range)))
          parent-child (partition 2 1 components)
          query {:find [(content-sym (last components))]
                 :in (vec (map path-sym components))
                 :where (into
                         [`[~(content-sym (first components)) :content/path
                            ~(path-sym (first components))]]
                         (mapcat
                          (fn [[parent child]]
                            (let [psym (content-sym parent)
                                  csym (content-sym child)
                                  path (path-sym child)]
                              `[[~csym :content/parent ~psym]
                                [~csym :content/path ~path]]))
                          parent-child))}]
      (ffirst (apply crux/q db
                     query
                     components)))

    :else
    (throw (ex-info "Resolving content-id requires a path (string starting with \"/\") or an id (uuid)"
                    {:id-or-path id-or-path}))))

(defn path
  "Generate path to content. Content must be a content ref."
  [db content]
  (let [id (content-ref content)
        segments (conj (parents-of db id) id)
        paths (into {}
                    (crux/q db
                            '{:find [?id ?path]
                              :where [[?id :content/path ?path]]
                              :in [[?id ...]]}
                            segments))]
    (str "/" (str/join "/" (map paths segments)))))