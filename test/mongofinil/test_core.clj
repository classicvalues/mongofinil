(ns mongofinil.test-core
  (:use midje.sweet)
  (:require [somnium.congomongo :as congo])
  (:require [mongofinil.core :as core])
  (:require [mongofinil.testing-utils :as utils]))

(utils/setup-test-db)
(utils/setup-midje)

(core/defmodel :xs
  :fields [;; simple
           {:name :x :findable true}
           {:name :y :findable false}
           {:name :z}
           {:name :w :findable true}

           ;; default
           {:name :dx :default 5}
           {:name :dy :default (fn [b] 6)}
           {:name :dz :default (fn [b] (-> b :x))}

           ;; ordered defaults
           {:name :def1 :default 5}
           {:name :def2 :default (fn [b] (inc (:def1 b)))}
           {:name :def3 :default (fn [b] (inc (:def2 b)))}
           {:name :def4 :default (fn [b] (:x b))}

           ;; transient
           {:name :disx :dissoc true}

           ;; keyword
           {:name :kw :keyword true}

           ;; validation
           {:name :valid-pos :default 5 :validator (fn [row] (when-not (pos? (:valid-pos row)) "Should be positive"))}]
  :validations [(fn [row] (when false "failing"))])

(fact "row findable functions are created and work"
  (let [obj1 (create! {:x 1 :y 2 :z 3 :w 4})
       obj2 (create! {:x 2 :y 3 :z 4 :w 5})]

    obj1 =not=> nil
    obj2 =not=> nil
    (-> obj1 :_id) =not=> nil
    (-> obj2 :_id) =not=> nil
    (dissoc obj1 :_id) => (contains {:x 1 :y 2 :z 3 :w 4})
    (dissoc obj2 :_id) => (contains {:x 2 :y 3 :z 4 :w 5})

    ;; success
    (find-by-x 1) => (contains obj1)
    (find-by-x! 1) => (contains obj1)
    (find-by-w 4) => (contains obj1)
    (find-by-w! 4) => (contains obj1)
    (find-by-x 2) => (contains obj2)
    (find-by-x! 2) => (contains obj2)
    (find-by-w 5) => (contains obj2)
    (find-by-w! 5) => (contains obj2)

    ;; failure
    (resolve 'find-by-y) => nil
    (resolve 'find-by-y!) => nil

    ;; empty
    (find-by-x 3) => nil
    (find-by-w 2) => nil
    (find-by-x! 3) => (throws Exception "Couldn't find row with :x=3 on collection :xs")
    (find-by-w! 2) => (throws Exception "Couldn't find row with :w=2 on collection :xs")))

(fact "find works"
  (let [obj (create! {:x 5 :y 6})
        id (:_id obj)]
    (find obj) => (contains obj)
    (find id) => (contains obj)
    (find (str id)) => (contains obj)
    (find (congo/object-id (str id))) => (contains obj)))

(fact "find-one works"
  (create! {:x 5 :y 6})
  (find-one) => (contains {:x 5 :y 6})
  (find-one :where {:y 6}) => (contains {:x 5 :y 6})
  (find-one :where {:y 7}) => nil)

(fact "keyword works"
  (create! {:x 5 :kw :asd}) => (contains {:x 5 :kw :asd})
  (find-one) => (contains {:x 5 :kw :asd}))


(fact "apply-defaults works"
  (core/apply-defaults [[ :x 5] [:y (fn [v] 6)] [:z 10]] {:z 9}) => (contains {:x 5 :y 6 :z 9}))


(fact "default works on creation"
  (create! {:x 7}) => (contains {:dx 5 :dy 6 :dz 7})
  (nu {:x 7}) => (contains {:dx 5 :dy 6 :dz 7}))

(fact "default works on loading"
  (congo/insert! :xs {:x 22})
  (find-by-x! 22) => (contains {:dx 5 :dy 6 :dz 22})
  (find-by-x 22) => (contains {:dx 5 :dy 6 :dz 22}))

(fact "defaults work in order"
  (create! {:x 11}) > (contains {:x 11 :def1 5 :def2 6 :def3 7 :def4 12}))

(fact "no nil defaults"
  (create! {:x 5}) =not=> (contains {:y anything}))


(fact "dissoc causes things not to be saved to the DB"
  (create! {:disx 5 :x 12}) => (contains {:disx 5})
  (find-by-x 12) =not=> (contains {:disx 5}))


(fact "ensure set works as planned"
  ;; add and check expected values
  (create! {:a "b" :c "d"})
  (let [old (find-one)]
    (-> old :a) => "b"
    (-> old :c) => "d"

    ;; set and check expected values
    (let [result (set-fields! old {:a "x" :e "f"})
          count (instance-count)
          new (find-one)]
      count => 1
      result => new
      (-> new :a) => "x"
      (-> new :c) => "d"
      (-> new :e) => "f")))

(fact "update! works"
  (instance-count) => 0
  (let [x (create! {:a :b :x :w})]
    (update! x {:c :d :a :B}))
  (instance-count) => 1
  (find-one) => (contains {:c "d" :a "B"})
  (find-one) =not=> (contains {:x "w"}))

(fact "dont try to serialize dissoced"
  (create! {:disx (fn [] "x")}))

(fact "check validations work"
  (let [x (create! {:valid-pos 5})]
    (valid? x) => true
    (validate! x) => x))

(fact "check validations fail properly"
  (create! {:valid-pos -1}) => (throws Exception "Should be positive"))

(fact "all works"
  (all) => (list)

  (create! {:x 5 :y 6})
  (count (all)) => 1

  (create! {:y 7 :z 8})
  (let [result (all)]
    result => seq?
    (count result) => 2
    result => (contains [(contains {:x 5 :y 6}) (contains {:y 7 :z 8})])))

(fact "where works"
  (where {:x 5}) => (list)

  (create! {:x 5 :y 6})
  (where {:x 5}) => (contains (contains {:x 5 :y 6}))
  (where {:x 6}) => (list)

  (create! {:y 7 :z 8})
  (let [result (where {:x 5 :y 6})]
    result => seq?
    (count result) => 1
    result => (contains [(contains {:x 5 :y 6})]))

  ;; check defaults
  (count (where {:dx 5})) => 2)

(future-fact "dissoc doesnt stop things being loaded from the DB"
             (congo/insert! :xs {:disx 55 :x 55})
             (find-by-x 55) => (contains {:disx 55}))

(future-fact "refresh function exists and works (refreshes from DB")


(future-fact "incorrectly named attributes are caught"
  (eval `(core/defmodel :ys :fields [{:a :b}])) => throws
  (eval `(core/defmodel :ys :fields [{:name :b :unexpected-field :y}])) => throws)

(future-fact "calling functions with the wrong signatures should give slightly useful error messages")