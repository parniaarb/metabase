(ns metabase.lib.remove-replace-test
  (:require
   #?@(:cljs ([metabase.test-runner.assert-exprs.approximately-equal]))
   [clojure.test :refer [are deftest is testing]]
   [medley.core :as m]
   [metabase.lib.core :as lib]
   [metabase.lib.options :as lib.options]
   [metabase.lib.remove-replace :as lib.remove-replace]
   [metabase.lib.test-metadata :as meta]
   [metabase.lib.test-util :as lib.tu]
   [metabase.lib.test-util.macros :as lib.tu.macros]))

#?(:cljs (comment metabase.test-runner.assert-exprs.approximately-equal/keep-me))

(deftest ^:parallel remove-clause-order-bys-test
  (let [query (-> lib.tu/venues-query
                  (lib/order-by (meta/field-metadata :venues :name))
                  (lib/order-by (meta/field-metadata :venues :name)))
        order-bys (lib/order-bys query)]
    (is (= 2 (count order-bys)))
    (is (= 1 (-> query
                 (lib/remove-clause (first order-bys))
                 (lib/order-bys)
                 count)))
    (is (nil? (-> query
                  (lib/remove-clause (first order-bys))
                  (lib/remove-clause (second order-bys))
                  (lib/order-bys))))))

(deftest ^:parallel remove-clause-filters-test
  (lib.tu.macros/with-testing-against-standard-queries query
    (let [query (-> query
                    (lib/filter (lib/= (meta/field-metadata :venues :price) 4))
                    (lib/filter (lib/= (meta/field-metadata :venues :name) "x")))
          filters (lib/filters query)]
      (is (= 2 (count filters)))
      (is (= 1 (-> query
                   (lib/remove-clause (first filters))
                   (lib/filters)
                   count)))
      (is (nil? (-> query
                    (lib/remove-clause (first filters))
                    (lib/remove-clause (second filters))
                    (lib/filters)))))))

(deftest ^:parallel remove-clause-join-conditions-test
  (let [query (-> lib.tu/venues-query
                  (lib/join (lib/join-clause (lib/query meta/metadata-provider (meta/table-metadata :categories))
                                             [(lib/= (meta/field-metadata :venues :price) 4)
                                              (lib/= (meta/field-metadata :venues :name) "x")])))
        conditions (lib/join-conditions (first (lib/joins query)))]
    (is (= 2 (count conditions)))
    (is (= [(second conditions)]
           (-> query
               (lib/remove-clause (first conditions))
               lib/joins
               first
               lib/join-conditions)))
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #"Cannot remove the final join condition"
          (-> query
              (lib/remove-clause (first conditions))
              (lib/remove-clause (second conditions)))))))

(deftest ^:parallel remove-clause-breakout-test
  (let [query (-> lib.tu/venues-query
                  (lib/aggregate (lib/count))
                  (lib/breakout (meta/field-metadata :venues :id))
                  (lib/breakout (meta/field-metadata :venues :name)))
        breakouts (lib/breakouts query)]
    (is (= 2 (count breakouts)))
    (is (=? [{:display-name "ID"}
             {:display-name "Name"}
             {:display-name "Count"}]
            (lib/returned-columns query)))
    (let [query'  (lib/remove-clause query (first breakouts))
          query'' (lib/remove-clause query' (second breakouts))]
      (is (= 1 (-> query' lib/breakouts count)))
      (is (=? [{:display-name "Name"}
               {:display-name "Count"}]
            (lib/returned-columns query')))
      (is (nil? (lib/breakouts query'')))
      (is (=? [{:display-name "Count"}]
            (lib/returned-columns query''))))
    (testing "removing with dependent should cascade"
      (is (=? {:stages [{:breakout [(second breakouts)]} (complement :filters)]}
              (-> query
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                  (lib/remove-clause 0 (first breakouts)))))
      (is (=? {:stages [{:breakout [(second breakouts)]}
                        (complement :fields)
                        (complement :filters)]}
              (-> query
                  (lib/append-stage)
                  (lib/with-fields [[:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"]])
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                  (lib/remove-clause 0 (first breakouts)))))
      (is (nil? (-> query
                    (lib/remove-clause 0 (second breakouts))
                    (lib/append-stage)
                    (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                    (lib/remove-clause 0 (first breakouts))
                    (lib/breakouts 0)))))))

(deftest ^:parallel remove-clause-fields-test
  (let [query (-> lib.tu/venues-query
                  (lib/expression "myadd" (lib/+ 1 (meta/field-metadata :venues :category-id)))
                  (lib/with-fields [(meta/field-metadata :venues :id) (meta/field-metadata :venues :name)]))
        fields (lib/fields query)]
    (is (= 3 (count fields)))
    (is (= 2 (-> query
                 (lib/remove-clause (first fields))
                 (lib/fields)
                 count)))
    (is (nil? (-> query
                  (lib/remove-clause (first fields))
                  (lib/remove-clause (second fields))
                  (lib/fields))))
    (testing "removing with dependent should cascade"
      (is (=? {:stages [{:fields (rest fields)} (complement :filters)]}
              (-> query
                (lib/append-stage)
                (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                (lib/remove-clause 0 (first fields)))))
      (is (=? {:stages [{:fields (rest fields)}
                        (complement :fields)
                        (complement :filters)]}
              (-> query
                (lib/append-stage)
                (lib/with-fields [[:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"]])
                (lib/append-stage)
                (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                (lib/remove-clause 0 (first fields)))))
      (is (nil? (-> query
                   (lib/remove-clause 0 (second fields))
                   (lib/append-stage)
                   (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                   (lib/remove-clause 0 (first fields))
                   (lib/fields 0)))))))

(deftest ^:parallel remove-clause-join-fields-test
  (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :categories))
                  (lib/join (-> (lib/join-clause lib.tu/venues-query
                                                 [(lib/= (meta/field-metadata :venues :price) 4)])
                                (lib/with-join-fields [(meta/field-metadata :venues :price)
                                                       (meta/field-metadata :venues :id)]))))
        fields (lib/join-fields (first (lib/joins query)))]
    (is (= 2 (count fields)))
    (is (= [(second fields)]
           (-> query
               (lib/remove-clause (first fields))
               lib/joins
               first
               lib/join-fields)))
    (is (nil? (-> query
                  (lib/remove-clause (first fields))
                  (lib/remove-clause (second fields))
                  lib/joins
                  first
                  lib/join-fields)))
    (testing "removing with dependent should cascade"
      (is (=? {:stages [{:joins [{:fields [(second fields)]}]} (complement :filters)]}
              (-> query
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "Venues__PRICE"] 1))
                  (lib/remove-clause 0 (first fields)))))
      (is (=? {:stages [{:joins [{:fields [(second fields)]}]} (complement :fields) (complement :filters)]}
            (-> query
                (lib/append-stage)
                (lib/with-fields [[:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "Venues__PRICE"]])
                (lib/append-stage)
                (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "Venues__PRICE"] 1))
                (lib/remove-clause 0 (first fields))))))))

(deftest ^:parallel replace-clause-join-with-all-fields-test
  (testing "Joins with :all fields selected can be handled (#31858)"
    (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :categories))
                    (lib/breakout (meta/field-metadata :categories :id))
                    (lib/aggregate (lib/sum (meta/field-metadata :categories :id)))
                    (lib/join (-> (lib/join-clause lib.tu/venues-query
                                                   [(lib/= (meta/field-metadata :venues :category-id)
                                                           (meta/field-metadata :categories :id))])
                                  (lib/with-join-fields :all))))
          query' (lib/order-by query (lib/aggregation-ref query 0))
          aggs (lib/aggregations query')]
      (is (=? {:lib/type :mbql/query
               :stages
               [{:lib/type :mbql.stage/mbql
                 :breakout [[:field {} (meta/id :categories :id)]]
                 :aggregation [[:avg {} [:length {} [:field {} (meta/id :categories :name)]]]]
                 :joins
                 [{:lib/type :mbql/join
                   :stages [{:lib/type :mbql.stage/mbql, :source-table (meta/id :venues)}]
                   :conditions [[:= {}
                                 [:field {:join-alias "Venues"} (meta/id :venues :category-id)]
                                 [:field {} (meta/id :categories :id)]]]
                   :fields :all
                   :alias "Venues"}]}]}
              (lib/replace-clause query'
                                  (first aggs)
                                  (lib/avg (lib/length (meta/field-metadata :categories :name)))))))))

(deftest ^:parallel remove-clause-aggregation-test
  (let [query (-> lib.tu/venues-query
                  (lib/aggregate (lib/sum (meta/field-metadata :venues :id)))
                  (lib/aggregate (lib/sum (meta/field-metadata :venues :price))))
        aggregations (lib/aggregations query)]
    (is (= 2 (count aggregations)))
    (is (= 1 (-> query
                 (lib/remove-clause (first aggregations))
                 (lib/aggregations)
                 count)))
    (is (nil? (-> query
                  (lib/remove-clause (first aggregations))
                  (lib/remove-clause (second aggregations))
                  (lib/aggregations))))
    (testing "removing with dependent should cascade"
      (is (=? {:stages [{:aggregation [(second aggregations)] :order-by (symbol "nil #_\"key is not present.\"")}
                        (complement :filters)]}
              (-> query
                  (as-> <> (lib/order-by <> (lib/aggregation-ref <> 0)))
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "sum"] 1))
                  (lib/remove-clause 0 (first aggregations))))))))

(deftest ^:parallel remove-clause-aggregation-with-ref-test
  (testing "removing an aggregation removes references in order-by (#12625)"
    (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                    (lib/aggregate (lib/count))
                    (lib/aggregate (lib/sum (meta/field-metadata :orders :total)))
                    (lib/aggregate (lib/sum (meta/field-metadata :orders :subtotal)))
                    (as-> $q (lib/order-by $q (lib/aggregation-ref $q 2))))
          aggregations (lib/aggregations query)]
      (is (=? {:stages [(complement :order-by)]}
              (lib/remove-clause query (last aggregations))))
      (let [query (lib/append-stage query)
            sum-col (m/find-first (comp #{"sum_2"} :lib/desired-column-alias) (lib/visible-columns query))
            query (lib/order-by query sum-col)]
        (is (=? {:stages [(complement :order-by) (complement :order-by)]}
                (lib/remove-clause query 0 (last aggregations))))))))

(deftest ^:parallel remove-clause-expression-test
  (let [query (-> lib.tu/venues-query
                  (lib/expression "a" (meta/field-metadata :venues :id))
                  (lib/expression "b" (meta/field-metadata :venues :price)))
        [expr-a expr-b :as expressions] (lib/expressions query)]
    (is (= 2 (count expressions)))
    (is (= 1 (-> query
                 (lib/remove-clause expr-a)
                 (lib/expressions)
                 count)))
    (is (nil? (-> query
                  (lib/remove-clause expr-a)
                  (lib/remove-clause expr-b)
                  (lib/expressions))))
    (testing "removing with dependent should cascade"
      (is (=? {:stages [{:expressions [expr-b] :order-by (symbol "nil #_\"key is not present.\"")}
                        (complement :filters)]}
              (-> query
                  (as-> <> (lib/order-by <> (lib/expression-ref <> "a")))
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "a"] 1))
                  (lib/remove-clause 0 expr-a)))))))

(deftest ^:parallel replace-clause-order-by-test
  (let [query (-> lib.tu/venues-query
                  (lib/filter (lib/= "myvenue" (meta/field-metadata :venues :name)))
                  (lib/order-by (meta/field-metadata :venues :name))
                  (lib/order-by (meta/field-metadata :venues :name)))
        order-bys (lib/order-bys query)]
    (is (= 2 (count order-bys)))
    (let [replaced (-> query
                       (lib/replace-clause (first order-bys) (lib/order-by-clause (meta/field-metadata :venues :id))))
          replaced-order-bys (lib/order-bys replaced)]
      (is (not= order-bys replaced-order-bys))
      (is (=? [:asc {} [:field {} (meta/id :venues :id)]]
              (first replaced-order-bys)))
      (is (= 2 (count replaced-order-bys)))
      (is (= (second order-bys) (second replaced-order-bys))))))

(deftest ^:parallel replace-clause-filters-test
  (let [query (-> lib.tu/venues-query
                  (lib/filter (lib/= (meta/field-metadata :venues :name) "myvenue"))
                  (lib/filter (lib/= (meta/field-metadata :venues :price) 2)))
        filters (lib/filters query)]
    (is (= 2 (count filters)))
    (let [replaced (-> query
                       (lib/replace-clause (first filters) (lib/= (meta/field-metadata :venues :id) 1)))
          replaced-filters (lib/filters replaced)]
      (is (not= filters replaced-filters))
      (is (=? [:= {} [:field {} (meta/id :venues :id)] 1]
              (first replaced-filters)))
      (is (= 2 (count replaced-filters)))
      (is (= (second filters) (second replaced-filters))))))

(deftest ^:parallel replace-clause-join-conditions-test
  (let [query (-> lib.tu/venues-query
                  (lib/join (lib/join-clause (lib/query meta/metadata-provider (meta/table-metadata :categories))
                                             [(lib/= (meta/field-metadata :venues :price) 4)])))
        conditions (lib/join-conditions (first (lib/joins query)))]
    (is (= 1 (count conditions)))
    (let [replaced (-> query
                       (lib/replace-clause (first conditions) (lib/= (meta/field-metadata :venues :id) 1)))
          replaced-conditions (lib/join-conditions (first (lib/joins replaced)))]
      (is (not= conditions replaced-conditions))
      (is (=? [:= {} [:field {} (meta/id :venues :id)] 1]
              (first replaced-conditions)))
      (is (= 1 (count replaced-conditions)))
      (is (= (second conditions) (second replaced-conditions))))))

(deftest ^:parallel replace-clause-join-fields-test
  (let [query (-> lib.tu/venues-query
                  (lib/join
                    (-> (lib/join-clause (lib/query meta/metadata-provider (meta/table-metadata :categories))
                                         [(lib/= (meta/field-metadata :venues :price) 4)])
                        (lib/with-join-fields
                          [(meta/field-metadata :categories :id)]))))
        fields (lib/join-fields (first (lib/joins query)))]
    (is (= 1 (count fields)))
    (let [replaced (-> query
                       (lib/replace-clause (first fields) (meta/field-metadata :categories :name)))
          replaced-fields (lib/join-fields (first (lib/joins replaced)))]
      (is (not= fields replaced-fields))
      (is (=? [:field {} (meta/id :categories :name)]
              (first replaced-fields)))
      (is (= 1 (count fields)))
      (is (= 1 (count replaced-fields))))))

(deftest ^:parallel replace-clause-breakout-test
  (let [query (-> lib.tu/venues-query
                  (lib/breakout (meta/field-metadata :venues :id))
                  (lib/breakout (meta/field-metadata :venues :name)))
        breakouts (lib/breakouts query)
        replaced (-> query
                     (lib/replace-clause (first breakouts) (meta/field-metadata :venues :price)))
        replaced-breakouts (lib/breakouts replaced)]
    (is (= 2 (count breakouts)))
    (is (=? [:field {} (meta/id :venues :price)]
            (first replaced-breakouts)))
    (is (not= breakouts replaced-breakouts))
    (is (= 2 (count replaced-breakouts)))
    (is (= (second breakouts) (second replaced-breakouts)))
    (testing "replacing with dependent should cascade"
      (is (=? {:stages [{:breakout [[:field {} (meta/id :venues :price)] (second breakouts)]}
                        (complement :filters)]}
              (-> query
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                  (lib/replace-clause 0 (first breakouts) (meta/field-metadata :venues :price)))))
      (is (not= breakouts (-> query
                              (lib/append-stage)
                              (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                              (lib/replace-clause 0 (second breakouts) (meta/field-metadata :venues :price))
                              (lib/breakouts 0)))))))

(deftest ^:parallel replace-clause-fields-test
  (let [query (-> lib.tu/venues-query
                  (lib/with-fields [(meta/field-metadata :venues :id) (meta/field-metadata :venues :name)]))
        fields (lib/fields query)
        replaced (-> query
                     (lib/replace-clause (first fields) (meta/field-metadata :venues :price)))
        replaced-fields (lib/fields replaced)]
    (is (= 2 (count fields)))
    (is (=? [:field {} (meta/id :venues :price)]
            (first replaced-fields)))
    (is (not= fields replaced-fields))
    (is (= 2 (count replaced-fields)))
    (is (= (second fields) (second replaced-fields)))
    (testing "replacing with dependent should cascade"
      (is (=? {:stages [{:fields [[:field {} (meta/id :venues :price)] (second fields)]}
                        (complement :filters)]}
              (-> query
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                  (lib/replace-clause 0 (first fields) (meta/field-metadata :venues :price)))))
      (is (not= fields (-> query
                           (lib/append-stage)
                           (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "ID"] 1))
                           (lib/replace-clause 0 (second fields) (meta/field-metadata :venues :price))
                           (lib/fields 0)))))))

(deftest ^:parallel replace-clause-aggregation-test
  (let [query (-> lib.tu/venues-query
                  (lib/aggregate (lib/sum (meta/field-metadata :venues :id)))
                  (lib/aggregate (lib/distinct (meta/field-metadata :venues :name))))
        aggregations (lib/aggregations query)
        replaced (-> query
                     (lib/replace-clause (first aggregations) (lib/sum (meta/field-metadata :venues :price))))
        replaced-aggregations (lib/aggregations replaced)]
    (is (= 2 (count aggregations)))
    (is (=? [:sum {} [:field {} (meta/id :venues :price)]]
            (first replaced-aggregations)))
    (is (not= aggregations replaced-aggregations))
    (is (= 2 (count replaced-aggregations)))
    (is (= (second aggregations) (second replaced-aggregations)))
    (testing "replacing with dependent should cascade"
      (is (=? {:stages [{:aggregation [[:sum {} [:field {} (meta/id :venues :price)]]
                                       (second aggregations)]
                         :expressions (symbol "nil #_\"key is not present.\"")}
                        (complement :filters)]}
              (-> query
                  (as-> <> (lib/expression <> "expr" (lib/aggregation-ref <> 0)))
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "sum"] 1))
                  (lib/replace-clause 0 (first aggregations) (lib/sum (meta/field-metadata :venues :price)))))))))

(deftest ^:parallel replace-metric-test
  (testing "replacing with metric should work"
    (let [metadata-provider (lib.tu/mock-metadata-provider
                             meta/metadata-provider
                             {:metrics  [{:id          100
                                          :name        "Sum of Cans"
                                          :table-id    (meta/id :venues)
                                          :definition  {:source-table (meta/id :venues)
                                                        :aggregation  [[:sum [:field (meta/id :venues :price) nil]]]
                                                        :filter       [:= [:field (meta/id :venues :price) nil] 4]}
                                          :description "Number of toucans plus number of pelicans"}]})
          query (-> (lib/query metadata-provider (meta/table-metadata :venues))
                    (lib/aggregate (lib/count)))]
      (is (=? {:stages [{:aggregation [[:metric {:lib/uuid string?} 100]]}]}
              (lib/replace-clause
               query
               (first (lib/aggregations query))
               (first (lib/available-metrics query)))))
      (is (=? {:stages [{:aggregation [[:count {:lib/uuid string?}]]}]}
              (-> query
                  (lib/replace-clause
                   (first (lib/aggregations query))
                   (first (lib/available-metrics query)))
                  (as-> $q (lib/replace-clause $q (first (lib/aggregations $q)) (lib/count)))))))))

(deftest ^:parallel replace-segment-test
  (testing "replacing with segment should work"
    (let [metadata-provider (lib.tu/mock-metadata-provider
                              meta/metadata-provider
                              {:segments  [{:id          100
                                            :name        "Price is 4"
                                            :definition  {:filter
                                                          [:= [:field (meta/id :venues :price) nil] 4]}
                                            :table-id    (meta/id :venues)}
                                           {:id          200
                                            :name        "Price is 5"
                                            :definition  {:filter
                                                          [:= [:field (meta/id :venues :price) nil] 5]}
                                            :table-id    (meta/id :venues)}]})
          query (-> (lib/query metadata-provider (meta/table-metadata :venues))
                    (lib/filter (lib/segment 100)))]
      (is (=? {:stages [{:filters [[:segment {:lib/uuid string?} 200]]}]}
              (lib/replace-clause
                query
                (first (lib/filters query))
                (second (lib/available-segments query))))))))

(deftest ^:parallel replace-clause-expression-test
  (let [query (-> lib.tu/venues-query
                  (lib/expression "a" (meta/field-metadata :venues :id))
                  (lib/expression "b" (meta/field-metadata :venues :name)))
        [expr-a expr-b :as expressions] (lib/expressions query)
        replaced (-> query
                     (lib/replace-clause expr-a (meta/field-metadata :venues :price)))
        [_repl-expr-a repl-expr-b :as replaced-expressions] (lib/expressions replaced)]
    (is (= 2 (count expressions)))
    (is (=? [[:field {:lib/expression-name "a"} (meta/id :venues :price)]
             expr-b]
            replaced-expressions))
    (is (not= expressions replaced-expressions))
    (is (= 2 (count replaced-expressions)))
    (is (= expr-b repl-expr-b))
    (testing "replacing with dependent should cascade"
      (is (=? {:stages [{:aggregation (symbol "nil #_\"key is not present.\"")
                         :expressions [[:field {:lib/expression-name "a"} (meta/id :venues :price)]
                                       expr-b]}
                        (complement :filters)]}
              (-> query
                  (as-> <> (lib/aggregate <> (lib/sum (lib/expression-ref <> "a"))))
                  (as-> <> (lib/with-fields <> [(lib/expression-ref <> "a")]))
                  (lib/append-stage)
                  (lib/filter (lib/= [:field {:lib/uuid (str (random-uuid)) :base-type :type/Integer} "a"] 1))
                  (lib/replace-clause 0 expr-a (meta/field-metadata :venues :price))))))
    (testing "replace with literal expression"
      (is (=? {:stages [{:expressions [[:value {:lib/expression-name "a" :effective-type :type/Integer} 999]
                                       expr-b]}]}
              (-> query
                  (lib/replace-clause 0 expr-a 999)))))))

(deftest ^:parallel replace-order-by-breakout-col-test
  (testing "issue #30980"
    (testing "Bucketing should keep order-by in sync"
      (let [query (lib/query meta/metadata-provider (meta/table-metadata :users))
            breakout-col (->> (lib/breakoutable-columns query)
                              (m/find-first (comp #{"LAST_LOGIN"} :name)))
            month (->> (lib/available-temporal-buckets query breakout-col)
                       (m/find-first (comp #{:month} :unit))
                       (lib/with-temporal-bucket breakout-col))
            day (->> (lib/available-temporal-buckets query breakout-col)
                     (m/find-first (comp #{:day} :unit))
                     (lib/with-temporal-bucket breakout-col))
            q2 (-> query
                   (lib/breakout month))
            cols (lib/orderable-columns q2)
            q3 (-> q2
                   (lib/order-by (first cols))
                   (lib/replace-clause (first (lib/breakouts q2)) day))
            q4 (lib/replace-clause q3 (first (lib/breakouts q3)) month)]
        (is (= :day (:temporal-unit (second (last (first (lib/order-bys q3)))))))
        (is (= :month (:temporal-unit (second (last (first (lib/order-bys q4)))))))))
    (testing "Binning should keep in order-by in sync"
      (let [query lib.tu/venues-query
            breakout-col (->> (lib/breakoutable-columns query)
                              (m/find-first (comp #{"PRICE"} :name)))
            ten (->> (lib/available-binning-strategies query breakout-col)
                     (m/find-first (comp #{"10 bins"} :display-name))
                     (lib/with-binning breakout-col))
            hundo (->> (lib/available-binning-strategies query breakout-col)
                       (m/find-first (comp #{"100 bins"} :display-name))
                       (lib/with-binning breakout-col))
            q2 (-> query
                   (lib/breakout ten))
            cols (lib/orderable-columns q2)
            q3 (-> q2
                   (lib/order-by (first cols))
                   (lib/replace-clause (first (lib/breakouts q2)) hundo))
            q4 (lib/replace-clause q3 (first (lib/breakouts q3)) ten)]
        (is (= 100 (:num-bins (:binning (second (last (first (lib/order-bys q3))))))))
        (is (= 10 (:num-bins (:binning (second (last (first (lib/order-bys q4))))))))))
    (testing "Replace the correct order-by bin when there are multiple"
      (let [query lib.tu/venues-query
            breakout-col (->> (lib/breakoutable-columns query)
                              (m/find-first (comp #{"PRICE"} :name)))
            ten (->> (lib/available-binning-strategies query breakout-col)
                     (m/find-first (comp #{"10 bins"} :display-name))
                     (lib/with-binning breakout-col))
            fiddy (->> (lib/available-binning-strategies query breakout-col)
                       (m/find-first (comp #{"50 bins"} :display-name))
                       (lib/with-binning breakout-col))
            hundo (->> (lib/available-binning-strategies query breakout-col)
                       (m/find-first (comp #{"100 bins"} :display-name))
                       (lib/with-binning breakout-col))
            auto (->> (lib/available-binning-strategies query breakout-col)
                      (m/find-first (comp #{"Auto bin"} :display-name))
                      (lib/with-binning breakout-col))
            q2 (-> query
                   (lib/breakout auto)
                   (lib/breakout ten)
                   (lib/breakout hundo))
            cols (lib/orderable-columns q2)
            q3 (-> q2
                   (lib/order-by (first cols))
                   (lib/order-by (second cols))
                   (lib/order-by (last cols)))
            ten-breakout (second (lib/breakouts q3))]
        (is (= ["Price: Auto binned" "Price: 10 bins" "Price: 100 bins"]
               (map (comp :display-name #(lib/display-info q2 %)) cols)))
        (is (= 10 (-> ten-breakout second :binning :num-bins)))
        (is (=? [{:strategy :default} {:num-bins 10} {:num-bins 100}]
                (map (comp :binning second last)
                     (lib/order-bys q3))))
        (is (=? [{:strategy :default} {:num-bins 50} {:num-bins 100}]
                (map (comp :binning second last)
                     (-> q3
                         (lib/replace-clause ten-breakout fiddy)
                         lib/order-bys))))))
    (testing "Replacing with a new field should remove the order by"
      (let [query lib.tu/venues-query
            breakout-col (->> (lib/breakoutable-columns query)
                              (m/find-first (comp #{"PRICE"} :name)))
            new-breakout-col (->> (lib/breakoutable-columns query)
                                  (m/find-first (comp #{"NAME"} :name)))
            ten (->> (lib/available-binning-strategies query breakout-col)
                     (m/find-first (comp #{"10 bins"} :display-name))
                     (lib/with-binning breakout-col))
            q2 (-> query
                   (lib/breakout ten))
            cols (lib/orderable-columns q2)
            q3 (-> q2
                   (lib/order-by (first cols)))
            ten-breakout (first (lib/breakouts q3))]
        (is (nil?
              (-> q3
                  (lib/replace-clause ten-breakout new-breakout-col)
                  lib/order-bys)))))
    (testing "Removing a breakout should remove the order by"
      (let [query lib.tu/venues-query
            breakout-col (->> (lib/breakoutable-columns query)
                              (m/find-first (comp #{"PRICE"} :name)))
            q2 (-> query
                   (lib/breakout breakout-col))
            cols (lib/orderable-columns q2)
            q3 (-> q2
                   (lib/order-by (first cols)))
            ten-breakout (first (lib/breakouts q3))]
        (is (nil?
              (-> q3
                  (lib/remove-clause ten-breakout)
                  lib/order-bys)))))))

(deftest ^:parallel rename-join-test
  (let [joined-column (-> (meta/field-metadata :venues :id)
                          (lib/with-join-alias "alias"))
        join-clause (-> (lib/join-clause
                         (meta/table-metadata :venues)
                         [(lib/= (meta/field-metadata :checkins :venue-id)
                                 joined-column)])
                        (lib/with-join-alias "alias"))]
    (testing "Missing join"
      (let [query (lib/query meta/metadata-provider (meta/table-metadata :checkins))]
        (testing "by name"
          (is (= query
                 (lib/rename-join query "old-name" "new-name"))))
        (testing "by index"
          (are [idx]
              (= query
                 (lib/rename-join query idx "new-name"))
              -1 0 1))
        (testing "by join clause"
          (is (= query
                 (lib/rename-join query join-clause "new-name"))))))
    (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :checkins))
                    (lib/join join-clause)
                    (lib/filter (lib/> joined-column 3)))]
      (testing "Simple renaming"
        (let [renamed {:lib/type :mbql/query
                       :database (meta/id)
                       :stages [{:lib/type :mbql.stage/mbql,
                                 :source-table (meta/id :checkins)
                                 :joins [{:lib/type :mbql/join
                                          :stages [{:lib/type :mbql.stage/mbql
                                                    :source-table (meta/id :venues)}]
                                          :conditions [[:=
                                                        {}
                                                        [:field
                                                         {:base-type :type/Integer
                                                          :effective-type :type/Integer}
                                                         (meta/id :checkins :venue-id)]
                                                        [:field
                                                         {:base-type :type/BigInteger
                                                          :effective-type :type/BigInteger
                                                          :join-alias "locale"}
                                                         (meta/id :venues :id)]]],
                                          :alias "locale"}]
                                 :filters [[:>
                                            {}
                                            [:field
                                             {:base-type :type/BigInteger
                                              :effective-type :type/BigInteger
                                              :join-alias "locale"}
                                             (meta/id :venues :id)]
                                            3]]}]}]
          (testing "by name"
            (is (=? renamed
                    (lib/rename-join query "alias" "locale"))))
          (testing "by index"
            (is (=? renamed
                    (lib/rename-join query 0 "locale"))))
          (testing "by join clause"
            (is (=? renamed
                    (lib/rename-join query (first (lib/joins query)) "locale"))))))
      (testing "Clashing renaming"
        (let [query' (-> query
                         (lib/join (-> (lib/join-clause
                                        (meta/table-metadata :users)
                                        [(lib/= (meta/field-metadata :checkins :user-id)
                                                (-> (meta/field-metadata :users :id)
                                                    (lib/with-join-alias "Users")))])
                                       (lib/with-join-alias "Users"))))
              renamed {:lib/type :mbql/query
                       :database (meta/id)
                       :stages [{:lib/type :mbql.stage/mbql,
                                 :source-table (meta/id :checkins)
                                 :joins [{:lib/type :mbql/join
                                          :stages [{:lib/type :mbql.stage/mbql
                                                    :source-table (meta/id :venues)}]
                                          :conditions [[:=
                                                        {}
                                                        [:field
                                                         {:base-type :type/Integer
                                                          :effective-type :type/Integer}
                                                         (meta/id :checkins :venue-id)]
                                                        [:field
                                                         {:base-type :type/BigInteger
                                                          :effective-type :type/BigInteger
                                                          :join-alias "alias"}
                                                         (meta/id :venues :id)]]],
                                          :alias "alias"}
                                         {:lib/type :mbql/join
                                          :stages [{:lib/type :mbql.stage/mbql
                                                    :source-table (meta/id :users)}]
                                          :conditions [[:=
                                                        {}
                                                        [:field
                                                         {:base-type :type/Integer
                                                          :effective-type :type/Integer}
                                                         (meta/id :checkins :user-id)]
                                                        [:field
                                                         {:base-type :type/BigInteger
                                                          :effective-type :type/BigInteger
                                                          :join-alias "alias_2"}
                                                         (meta/id :users :id)]]],
                                          :alias "alias_2"}]
                                 :filters [[:>
                                            {}
                                            [:field
                                             {:base-type :type/BigInteger
                                              :effective-type :type/BigInteger
                                              :join-alias "alias"}
                                             (meta/id :venues :id)]
                                            3]]}]}]
          (testing "by name"
            (is (=? renamed
                    (lib/rename-join query' "Users" "alias"))))
          (testing "by index"
            (is (=? renamed
                    (lib/rename-join query' 1 "alias"))))
          (testing "by join clause"
            (is (=? renamed
                    (lib/rename-join query' (second (lib/joins query')) "alias")))))))))


(deftest ^:parallel remove-join-test
  (testing "Missing join"
    (let [query (lib/query meta/metadata-provider (meta/table-metadata :checkins))]
      (testing "by index"
        (are [idx] (= query
                      (lib/remove-join query 0 idx))
          -1 0 1))
      (testing "by name"
        (is (= query
               (lib/remove-join query 0 "old-alias"))))))
  (let [join-alias "alias"
        joined-column (-> (meta/field-metadata :venues :id)
                          (lib/with-join-alias join-alias))
        query (-> (lib/query meta/metadata-provider (meta/table-metadata :checkins))
                  (lib/join (-> (lib/join-clause
                                 (meta/table-metadata :venues)
                                 [(lib/= (meta/field-metadata :checkins :venue-id)
                                         (-> (meta/field-metadata :venues :id)
                                             (lib/with-join-alias join-alias)))])
                                (lib/with-join-fields :all)
                                (lib/with-join-alias join-alias)))
                  (lib/filter (lib/> joined-column 3)))]
    (testing "Removing the last join"
      (let [result {:lib/type :mbql/query
                    :database (meta/id)
                    :stages   [{:lib/type     :mbql.stage/mbql,
                                :source-table (meta/id :checkins)}]
                    :joins    (symbol "nil #_\"key is not present.\"")
                    :filters  (symbol "nil #_\"key is not present.\"")}]
        (testing "by index"
          (is (=? result
                  (lib/remove-join query 0 0))))
        (testing "by name"
          (is (=? result
                  (lib/remove-join query 0 join-alias))))
        (testing "by join-clause"
          (is (=? result
                  (lib/remove-join query 0 (first (lib/joins query))))))))
    (testing "Removing one of the joins"
      (let [filter-field [:field {:base-type :type/DateTime} "Users__LAST_LOGIN"]
            query' (-> query
                       (lib/join (-> (lib/join-clause
                                      (meta/table-metadata :users)
                                      [(lib/= (meta/field-metadata :checkins :user-id)
                                              (-> (meta/field-metadata :users :id)
                                                  (lib/with-join-alias "Users")))])
                                     (lib/with-join-fields :all)
                                     (lib/with-join-alias "Users")))
                       lib/append-stage
                       (lib/filter (lib/not-null (lib.options/ensure-uuid filter-field)))
                       (lib/breakout (lib.options/ensure-uuid filter-field)))
            result {:lib/type :mbql/query
                    :database (meta/id)
                    :stages [{:lib/type :mbql.stage/mbql,
                              :source-table (meta/id :checkins)
                              :joins [{:lib/type :mbql/join
                                       :stages [{:lib/type :mbql.stage/mbql
                                                 :source-table (meta/id :venues)}]
                                       :fields :all
                                       :conditions [[:=
                                                     {}
                                                     [:field
                                                      {:base-type :type/Integer
                                                       :effective-type :type/Integer}
                                                      (meta/id :checkins :venue-id)]
                                                     [:field
                                                      {:base-type :type/BigInteger
                                                       :effective-type :type/BigInteger
                                                       :join-alias join-alias}
                                                      (meta/id :venues :id)]]],
                                       :alias join-alias}]
                              :filters [[:>
                                         {}
                                         [:field
                                          {:base-type :type/BigInteger,
                                           :effective-type :type/BigInteger,
                                           :join-alias join-alias}
                                          (meta/id :venues :id)]
                                         3]]}
                             {:filters  (symbol "nil #_\"key is not present.\"")
                              :breakout (symbol "nil #_\"key is not present.\"")}]}]
        (testing "by index"
          (is (=? result
                  (lib/remove-join query' 0 1))))
        (testing "by name"
          (is (=? result
                  (lib/remove-join query' 0 "Users"))))
        (testing "by join-clause"
          (is (=? result
                  (lib/remove-join query' 0 (second (lib/joins query' 0)))))
          (testing "using remove-clause"
            (is (=? result
                    (lib/remove-clause query' 0 (second (lib/joins query' 0)))))))))))

(deftest ^:parallel remove-dependent-join-test
  (let [first-join-clause (-> (lib/join-clause
                               (meta/table-metadata :checkins)
                               [(lib/= (meta/field-metadata :venues :id)
                                       (-> (meta/field-metadata :checkins :venue-id)
                                           (lib/with-join-alias "Checkins")))])
                              (lib/with-join-fields :all)
                              (lib/with-join-alias "Checkins"))
        query (-> (lib/query meta/metadata-provider (meta/table-metadata :venues))
                  (lib/join first-join-clause)
                  (lib/join (-> (lib/join-clause
                                 (meta/table-metadata :users)
                                 [(lib/= (-> (meta/field-metadata :checkins :user-id)
                                             (lib/with-join-alias "Checkins"))
                                         (-> (meta/field-metadata :users :id)
                                             (lib/with-join-alias "Users")))])
                                (lib/with-join-fields :all)
                                (lib/with-join-alias "Users"))))]
    (is (nil? (lib/joins (lib/remove-clause query 0 first-join-clause))))))

(deftest ^:parallel remove-dependent-join-from-subsequent-stage-test
  (testing "Join from previous stage used in join can be removed (#34404)"
    (let [join-query (-> (lib/query meta/metadata-provider (meta/table-metadata :venues))
                         (lib/join (-> (lib/join-clause
                                        (meta/table-metadata :checkins)
                                        [(lib/= (meta/field-metadata :venues :id)
                                                (-> (meta/field-metadata :checkins :venue-id)
                                                    (lib/with-join-alias "Checkins")))])
                                       (lib/with-join-fields :all)
                                       (lib/with-join-alias "Checkins"))))
          breakout-col (m/find-first (comp #{(meta/id :checkins :venue-id)} :id)
                                     (lib/breakoutable-columns join-query))
          breakout-query (-> join-query
                             (lib/breakout breakout-col)
                             (lib/aggregate (lib/count))
                             (lib/append-stage))
          query (-> breakout-query
                    (lib/join (-> (lib/join-clause
                                   (meta/table-metadata :checkins)
                                   [(lib/= (first (lib/returned-columns breakout-query))
                                           (-> (meta/field-metadata :checkins :venue-id)
                                               (lib/with-join-alias "Checkins2")))])
                                  (lib/with-join-fields :all)
                                  (lib/with-join-alias "Checkins2"))))]
      (is (nil? (lib/joins (lib/remove-clause query 0 (first (lib/joins query 0)))))))))

(deftest ^:parallel replace-join-test
  (let [query             lib.tu/query-with-join
        expected-original {:stages [{:joins [{:lib/type :mbql/join, :alias "Cat", :fields :all}]}]}
        [original-join]   (lib/joins query)
        new-join          (lib/with-join-fields original-join :none)]
    (is (=? expected-original
            query))
    (testing "dangling join-spec leads to no change"
      (are [join-spec] (=? expected-original
                           (lib/replace-join query 0 join-spec new-join))
        -1 1 "missing-alias"))
    (testing "replace using index"
      (is (=? {:stages [{:joins [{:lib/type :mbql/join, :alias "Cat", :fields :none}]}]}
              (lib/replace-join query 0 new-join))))
    (testing "replace using alias"
      (is (=? {:stages [{:joins [{:lib/type :mbql/join, :alias "Cat", :fields :none}]}]}
              (lib/replace-join query "Cat" new-join))))
    (testing "replace using replace-clause"
      (is (=? {:stages [{:joins [{:lib/type :mbql/join, :alias "Cat", :fields :none}]}]}
              (lib/replace-clause query original-join new-join))))
    (let [join-alias "alias"
          price-name (str join-alias "__PRICE")
          joined-column (-> (meta/field-metadata :venues :id)
                            (lib/with-join-alias join-alias))
          users-alias "Users"
          last-login-name (str users-alias "__LAST_LOGIN")
          breakout-field [:field {:base-type :type/DateTime} last-login-name]
          query (-> (lib/query meta/metadata-provider (meta/table-metadata :checkins))
                    (lib/join (-> (lib/join-clause
                                   (meta/table-metadata :venues)
                                   [(lib/= (meta/field-metadata :checkins :venue-id)
                                           (-> (meta/field-metadata :venues :id)
                                               (lib/with-join-alias join-alias)))])
                                  (lib/with-join-fields :all)
                                  (lib/with-join-alias join-alias)))
                    (lib/filter (lib/> joined-column 3))
                    (lib/join (-> (lib/join-clause
                                   (meta/table-metadata :users)
                                   [(lib/= (meta/field-metadata :checkins :user-id)
                                           (-> (meta/field-metadata :users :id)
                                               (lib/with-join-alias users-alias)))])
                                  (lib/with-join-fields :all)
                                  (lib/with-join-alias users-alias)))
                    lib/append-stage
                    (lib/filter (lib/< (lib.options/ensure-uuid [:field {:base-type :type/Integer} price-name]) 3))
                    (lib/filter (lib/not-null (lib.options/ensure-uuid breakout-field)))
                    (lib/breakout (lib.options/ensure-uuid breakout-field)))
          [join0 join1] (lib/joins query 0)]
      (testing "effects are reflected in subsequent stages"
        (is (=? {:stages [{:joins [{:fields :none, :alias join-alias}
                                   {:fields :all, :alias users-alias}]
                           :filters [[:> {} [:field {:join-alias join-alias} (meta/id :venues :id)] 3]]}
                          {:filters [[:not-null {} [:field {} last-login-name]]]
                           :breakout [[:field {} last-login-name]]}]}
                (lib/replace-clause query 0 join0 (lib/with-join-fields join0 :none)))))
      (testing "replacing with nil removes the join"
        (is (=? {:stages
                 [{:joins [{:fields :all, :alias join-alias}]
                   :filters [[:> {} [:field {:join-alias join-alias} (meta/id :venues :id)] 3]]}
                  {:filters [[:< {} [:field {} price-name] 3]]}]}
                (lib/replace-clause query 0 join1 nil)))))))

(deftest ^:parallel replace-join-with-new-join-test
  (let [filter-1   #(lib/= (meta/field-metadata :orders :product-id)
                           (meta/field-metadata :products :id))
        filter-2   #(lib/=
                      (meta/field-metadata :orders :created-at)
                      (meta/field-metadata :products :created-at))
        query      (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                       (lib/join (lib/join-clause (meta/table-metadata :products) [(filter-1)])))
        new-clause (lib/join-clause (meta/table-metadata :products) [(filter-2)])]
    (testing "New clause gets alias"
      (is (=? {:stages [{:joins [{:alias "Products - Created At"}]}]}
              (lib/replace-clause query -1 (first (lib/joins query)) new-clause))))
    (testing "New clause alias is maintained if table is maintained"
      (let [multi-query (-> query
                            (lib/join (lib/join-clause (meta/table-metadata :products) [(filter-2)]))
                            (lib/join (lib/join-clause (meta/table-metadata :products) [(filter-2)])))]
        (is (= ["Products" "Products - Created At" "Products - Created At_2"]
               (->> multi-query
                    :stages
                    first
                    :joins
                    (map :alias))
               (->> (lib/replace-clause multi-query -1 (second (lib/joins multi-query)) new-clause)
                    :stages
                    first
                    :joins
                    (map :alias))))))
    (testing "New clause alias reflects new table"
      (let [multi-query (-> query
                            (lib/join (lib/join-clause (meta/table-metadata :products) [(filter-2)]))
                            (lib/join (lib/join-clause (meta/table-metadata :products) [(filter-2)])))]
        (is (= ["Products" "Users" "Products - Created At_2"]
               (->> (lib/replace-clause multi-query -1 (second (lib/joins multi-query))
                                        (lib/join-clause (meta/table-metadata :users)
                                                         [(lib/= (meta/field-metadata :orders :user-id)
                                                                 (meta/field-metadata :users :id))]))
                    :stages
                    first
                    :joins
                    (map :alias))))))))

(deftest ^:parallel remove-first-in-long-series-of-join-test
  (testing "Recursive join removal (#35049)"
    (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :reviews))
                    (lib/join (meta/table-metadata :products))
                    (lib/join (lib/join-clause (meta/table-metadata :orders) [(lib/= (lib/with-join-alias (meta/field-metadata :products :id) "Products")
                                                                                     (lib/with-join-alias (meta/field-metadata :orders :product-id) "Orders"))]))
                    (lib/join (meta/table-metadata :people)))]
      (is (=?
            {:stages [(complement :joins)]}
            (lib/remove-clause query -1 (first (lib/joins query))))))))

(deftest ^:parallel removing-aggregation-leaves-breakouts
  (testing "Removing aggregation leaves breakouts (#28609)"
    (let [query (-> lib.tu/venues-query
                    (lib/aggregate (lib/count)))
          query (reduce lib/breakout
                        query
                        (lib/breakoutable-columns query))
          result (lib/remove-clause query (first (lib/aggregations query)))]
      (is (seq (lib/breakouts result)))
      (is (empty? (lib/aggregations result)))
      (is (= (lib/breakouts query) (lib/breakouts result))))))

(deftest ^:parallel simple-tweak-expression-test
  (let [table (lib/query meta/metadata-provider (meta/table-metadata :orders))
        base (lib/expression table "Tax Rate" (lib// (meta/field-metadata :orders :tax)
                                                     (meta/field-metadata :orders :total)))
        query (lib/filter base (lib/> (lib/expression-ref base "Tax Rate") 6))
        orig-expr (first (lib/expressions query))
        new-expr (-> (lib/* (lib// (meta/field-metadata :orders :tax)
                                   (meta/field-metadata :orders :total))
                            100)
                     (lib/with-expression-name "Tax Rate"))]
    (is (=? {:lib/type :mbql/query,
             :stages
             [{:lib/type :mbql.stage/mbql
               :source-table (meta/id :orders)
               :expressions
               [[:* {:lib/expression-name "Tax Rate"}
                 [:/ {}
                  [:field {:effective-type :type/Float} (meta/id :orders :tax)]
                  [:field {:effective-type :type/Float} (meta/id :orders :total)]]
                 100]]
               :filters [[:> {} [:expression {:effective-type :type/Float} "Tax Rate"] 6]]}]}
            (lib/replace-clause query orig-expr new-expr)))))

(deftest ^:parallel simple-tweak-aggregation-test
  (let [base (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                 (lib/aggregate (lib/with-expression-name
                                  (lib// (lib/sum (meta/field-metadata :orders :tax))
                                         (lib/count (meta/field-metadata :orders :tax)))
                                  "Avg Tax"))
                 (lib/breakout (meta/field-metadata :orders :user-id))
                 lib/append-stage)
        avg-tax-col (second (lib/returned-columns base))
        query (lib/filter base (lib/> avg-tax-col 0.06))
        orig-agg (first (lib/aggregations query 0))
        new-agg (-> (lib/avg (meta/field-metadata :orders :tax))
                    (lib/with-expression-name "Avg Tax"))]
    (is (=? {:stages
             [{:lib/type :mbql.stage/mbql
               :source-table (meta/id :orders)
               :aggregation [[:avg {:name "Avg Tax", :display-name "Avg Tax"}
                              [:field {:effective-type :type/Float} (meta/id :orders :tax)]]]
               :breakout [[:field {:effective-type :type/Integer} (meta/id :orders :user-id)]]}
              {:lib/type :mbql.stage/mbql,
               :filters [[:> {} [:field {:effective-type :type/Float} "Avg Tax"] 0.06]]}]}
            (lib/replace-clause query 0 orig-agg new-agg)))))

(deftest ^:parallel replace-clause-uses-custom-expression-name-test
  (let [query (-> lib.tu/venues-query
                  (lib/expression "expr" (lib/+ 1 1)))]
    (is (=? [[:+ {:lib/expression-name "expr"} 1 1]]
            (lib/expressions query)))
    (is (=? [[:value {:lib/expression-name "evaluated expr"
                      :name (symbol "nil #_\"key is not present.\"")
                      :display-name (symbol "nil #_\"key is not present.\"")
                      :effective-type :type/Integer}
              2]]
            (-> query
                (lib/replace-clause (first (lib/expressions query))
                                    (lib/with-expression-name 2 "evaluated expr"))
                lib/expressions)))))

(deftest ^:parallel normalize-fields-clauses-test
  (testing "queries with no :fields clauses should not be changed"
    (are [query] (= query (lib.remove-replace/normalize-fields-clauses query))
      lib.tu/query-with-join
      lib.tu/query-with-self-join
      lib.tu/venues-query))
  (testing "a :fields clause with extras is retained"
    (let [base     (lib/query meta/metadata-provider (meta/table-metadata :orders))
          viz      (lib/visible-columns base)
          category (m/find-first #(= (:name %) "CATEGORY") viz)
          query    (lib/add-field base 0 category)]
      (is (= base  (lib.remove-replace/normalize-fields-clauses base)))
      (is (= query (lib.remove-replace/normalize-fields-clauses query)))))
  (testing "a :fields clause with a default field removed is retained"
    (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                    (lib/remove-field 0 (assoc (meta/field-metadata :orders :tax)
                                               :lib/source :source/table-defaults)))]
      (is (= 8 (-> query :stages first :fields count)))
      (is (= query (lib.remove-replace/normalize-fields-clauses query)))))
  (testing "if :fields clause matches the defaults it is dropped"
    (testing "removing then restoring a field"
      (let [tax     (assoc (meta/field-metadata :orders :tax)
                           :lib/source :source/table-defaults)
            tax-ref (lib/ref tax)
            query   (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                        (lib/remove-field 0 tax)
                        ;; Can't use lib/add-field here because it will normalize the :fields clause.
                        (update-in [:stages 0 :fields] conj tax-ref))]
        (is (= 9 (-> query :stages first :fields count)))
        (is (nil? (-> query
                      lib.remove-replace/normalize-fields-clauses
                      :stages
                      first
                      :fields)))))
    (testing "adding and dropping and implicit join field"
      (let [category (assoc (meta/field-metadata :products :category)
                            :lib/source :source/implicitly-joinable)
            query    (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                         (lib/add-field 0 category)
                         ;; Can't use remove-field itself; it will normalize the fields clause.
                         (update-in [:stages 0 :fields] #(remove (comp #{(meta/id :products :category)} last) %)))]
        (is (= 9 (-> query :stages first :fields count)))
        (is (nil? (-> query
                      lib.remove-replace/normalize-fields-clauses
                      :stages
                      first
                      :fields))))))
  (testing ":fields clauses on joins"
    (testing "are preserved if :none or :all"
      (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                      (lib/join (lib/join-clause (meta/table-metadata :products)
                                                 [(lib/= (meta/field-metadata :orders :product-id)
                                                         (meta/field-metadata :products :id))])))
            none  (assoc-in query [:stages 0 :joins 0 :fields] :none)]
        (is (= :all (-> query :stages first :joins first :fields)))
        (is (= query (lib.remove-replace/normalize-fields-clauses query)))
        (is (= none  (lib.remove-replace/normalize-fields-clauses none)))))
    (testing "are preserved if they do not match the defaults"
      (let [join   (-> (lib/join-clause (meta/table-metadata :products)
                                        [(lib/= (meta/field-metadata :orders :product-id)
                                                (meta/field-metadata :products :id))])
                       (lib/with-join-fields (for [field (take 4 (meta/fields :products))]
                                               (meta/field-metadata :products field))))
            query  (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                       (lib/join join))]
        (is (= 4 (-> query :stages first :joins first :fields count)))
        (is (= query (lib.remove-replace/normalize-fields-clauses query)))))
    (testing "are replaced with :all if they include all the defaults"
      (let [join   (-> (lib/join-clause (meta/table-metadata :products)
                                        [(lib/= (meta/field-metadata :orders :product-id)
                                                (meta/field-metadata :products :id))])
                       (lib/with-join-fields (for [field (meta/fields :products)]
                                               (meta/field-metadata :products field))))
            query  (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                       (lib/join join))]
        (is (= 8 (-> query :stages first :joins first :fields count)))
        (is (= (assoc-in query [:stages 0 :joins 0 :fields] :all)
               (lib.remove-replace/normalize-fields-clauses query)))))))
