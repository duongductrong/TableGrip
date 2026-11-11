package dev.tablegrip.core.completion

import dev.tablegrip.core.completion.JsonNode.Shape

/**
 * What an engine's HTTP console completes: its methods and known endpoints, the placeholders that take container names,
 * and [other], for what the console takes that isn't a request (Weaviate's GraphQL).
 */
class HttpEndpoints(
    val list: List<Endpoint>,
    val containerPlaceholders: Set<String>,
    val methods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH"),
    val other: ((text: String, caret: Int, catalog: ConsoleCatalog) -> CompletionResult)? = null,
) {
    /** The endpoint [path] goes to with [method] and the values of its placeholders; endpoints with a body first. */
    fun match(method: String, path: String): Pair<Endpoint, Map<String, String>>? {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return list
            .filter { e -> method in e.methods && e.segments.size == segments.size && e.segments.indices.all { e.segments[it] == segments[it] || HttpConsoleCompleter.isPlaceholder(e.segments[it]) } }
            // The most literal match wins: `/_cat/indices` over `/{index}/{id}`.
            .sortedWith(compareBy({ e -> e.segments.count(HttpConsoleCompleter::isPlaceholder) }, { e -> if (e.body == null) 1 else 0 }))
            .firstOrNull()
            ?.let { e -> e to e.segments.indices.filter { HttpConsoleCompleter.isPlaceholder(e.segments[it]) }.associate { e.segments[it].trim('{', '}') to decode(segments[it]) } }
    }

    private fun decode(segment: String) = runCatching { java.net.URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(segment)

    companion object {
        fun of(family: dev.tablegrip.core.connection.EngineFamily): HttpEndpoints? =
            when (family) {
                dev.tablegrip.core.connection.EngineFamily.Elasticsearch -> Elasticsearch
                dev.tablegrip.core.connection.EngineFamily.Solr -> Solr
                dev.tablegrip.core.connection.EngineFamily.Meilisearch -> Meilisearch
                dev.tablegrip.core.connection.EngineFamily.CouchDB -> CouchDB
                dev.tablegrip.core.connection.EngineFamily.Weaviate -> Weaviate
                dev.tablegrip.core.connection.EngineFamily.HBase -> HBase
                dev.tablegrip.core.connection.EngineFamily.Consul -> Consul
                dev.tablegrip.core.connection.EngineFamily.Qdrant -> Qdrant
                dev.tablegrip.core.connection.EngineFamily.Milvus -> Milvus
                dev.tablegrip.core.connection.EngineFamily.RabbitMQ -> RabbitMQ
                dev.tablegrip.core.connection.EngineFamily.Chroma -> Chroma
                dev.tablegrip.core.connection.EngineFamily.Nacos -> Nacos
                else -> null
            }

        // region Building blocks

        private fun obj(vararg keys: Pair<String, JsonNode>, fields: JsonNode? = null, any: JsonNode? = null, shape: Shape = Shape.Object) =
            JsonNode(shape, linkedMapOf(*keys), fields, any)

        private val scalar = JsonNode()
        private val bool = JsonNode(values = listOf("true", "false"))
        private val field = JsonNode(fieldValues = true)
        private val container = JsonNode(containerValues = true)
        private val fieldList = JsonNode(Shape.Array, element = field)
        private val list = JsonNode(Shape.Array, element = scalar)
        private val anyObject = obj()

        private fun choice(vararg values: String) = JsonNode(values = values.map { "\"$it\"" })

        private fun many(element: JsonNode) = JsonNode(Shape.Array, element = element)

        private fun get(vararg paths: String) = paths.map { Endpoint(listOf("GET"), it) }

        // endregion

        // region Elasticsearch / OpenSearch

        private val EsQuery: JsonNode = obj()
        private val EsAggs: JsonNode = obj()

        init {
            val text = obj("query" to scalar, "operator" to choice("and", "or"), "fuzziness" to choice("AUTO"), "analyzer" to scalar, "boost" to scalar, "minimum_should_match" to scalar, shape = Shape.Scalar)
            val term = obj("value" to scalar, "boost" to scalar, "case_insensitive" to bool, shape = Shape.Scalar)
            val range = obj("gt" to scalar, "gte" to scalar, "lt" to scalar, "lte" to scalar, "format" to scalar, "time_zone" to scalar, "boost" to scalar)
            val queryList = JsonNode(Shape.Array, element = EsQuery)
            EsQuery.keys.putAll(
                linkedMapOf(
                    "match" to obj(fields = text),
                    "match_phrase" to obj(fields = text),
                    "match_phrase_prefix" to obj(fields = text),
                    "multi_match" to obj("query" to scalar, "fields" to fieldList, "type" to choice("best_fields", "most_fields", "cross_fields", "phrase", "phrase_prefix", "bool_prefix"), "operator" to choice("and", "or"), "fuzziness" to scalar),
                    "term" to obj(fields = term),
                    "terms" to obj(fields = JsonNode(Shape.Array, element = scalar)),
                    "range" to obj(fields = range),
                    "exists" to obj("field" to field),
                    "bool" to obj("must" to queryList, "filter" to queryList, "should" to queryList, "must_not" to queryList, "minimum_should_match" to scalar, "boost" to scalar),
                    "match_all" to obj("boost" to scalar),
                    "match_none" to obj(),
                    "ids" to obj("values" to list),
                    "prefix" to obj(fields = term),
                    "wildcard" to obj(fields = term),
                    "regexp" to obj(fields = term),
                    "fuzzy" to obj(fields = term),
                    "query_string" to obj("query" to scalar, "default_field" to field, "fields" to fieldList, "default_operator" to choice("AND", "OR"), "analyze_wildcard" to bool),
                    "simple_query_string" to obj("query" to scalar, "fields" to fieldList, "default_operator" to choice("AND", "OR")),
                    "nested" to obj("path" to field, "query" to EsQuery, "score_mode" to choice("avg", "max", "min", "none", "sum")),
                    "constant_score" to obj("filter" to EsQuery, "boost" to scalar),
                    "dis_max" to obj("queries" to queryList, "tie_breaker" to scalar),
                    "function_score" to obj("query" to EsQuery, "functions" to list, "score_mode" to scalar, "boost_mode" to scalar),
                    "knn" to obj("field" to field, "query_vector" to list, "k" to scalar, "num_candidates" to scalar, "filter" to EsQuery),
                    "script" to obj("script" to obj("source" to scalar, "params" to anyObject, "lang" to scalar)),
                ),
            )
            val metric = obj("field" to field, "missing" to scalar, "script" to scalar)
            val body =
                obj(
                    "terms" to obj("field" to field, "size" to scalar, "order" to anyObject, "min_doc_count" to scalar, "missing" to scalar, "include" to scalar, "exclude" to scalar),
                    "avg" to metric, "sum" to metric, "min" to metric, "max" to metric, "value_count" to metric, "cardinality" to metric, "stats" to metric,
                    "extended_stats" to metric,
                    "percentiles" to obj("field" to field, "percents" to list),
                    "date_histogram" to
                        obj("field" to field, "calendar_interval" to choice("minute", "hour", "day", "week", "month", "quarter", "year"), "fixed_interval" to scalar, "format" to scalar, "time_zone" to scalar, "min_doc_count" to scalar),
                    "histogram" to obj("field" to field, "interval" to scalar, "min_doc_count" to scalar),
                    "range" to obj("field" to field, "ranges" to many(obj("from" to scalar, "to" to scalar, "key" to scalar))),
                    "filter" to EsQuery,
                    "filters" to obj("filters" to obj(any = EsQuery)),
                    "top_hits" to obj("size" to scalar, "sort" to list, "_source" to fieldList),
                    "nested" to obj("path" to field),
                    "composite" to obj("size" to scalar, "sources" to list, "after" to anyObject),
                    "aggs" to EsAggs,
                    "aggregations" to EsAggs,
                )
            EsAggs.anyKey = body
        }

        private val EsSort = JsonNode(Shape.Array, element = JsonNode(fieldKeys = obj("order" to choice("asc", "desc"), "mode" to choice("min", "max", "avg", "sum", "median"), "missing" to scalar, "unmapped_type" to scalar, shape = Shape.Scalar), fieldValues = true))

        private val EsSearch =
            obj(
                "query" to EsQuery,
                "size" to scalar,
                "from" to scalar,
                "sort" to EsSort,
                "_source" to JsonNode(Shape.Scalar, linkedMapOf("includes" to fieldList, "excludes" to fieldList), element = field, values = listOf("true", "false")),
                "aggs" to EsAggs,
                "aggregations" to EsAggs,
                "highlight" to obj("fields" to obj(fields = obj("number_of_fragments" to scalar, "fragment_size" to scalar)), "pre_tags" to list, "post_tags" to list),
                "track_total_hits" to bool,
                "post_filter" to EsQuery,
                "collapse" to obj("field" to field),
                "search_after" to list,
                "fields" to fieldList,
                "min_score" to scalar,
                "timeout" to scalar,
                "explain" to bool,
                "version" to bool,
                "seq_no_primary_term" to bool,
                "profile" to bool,
                "runtime_mappings" to anyObject,
                "knn" to obj("field" to field, "query_vector" to list, "k" to scalar, "num_candidates" to scalar, "filter" to EsQuery),
            )

        private val EsDocument = obj(fields = scalar)
        private val EsMapping = obj("properties" to obj(fields = obj("type" to choice("text", "keyword", "long", "integer", "double", "float", "boolean", "date", "object", "nested", "geo_point", "dense_vector"))), "dynamic" to choice("true", "false", "strict"))

        private val Elasticsearch =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("GET", "POST"), "/{index}/_search", EsSearch),
                    Endpoint(listOf("GET", "POST"), "/_search", EsSearch),
                    Endpoint(listOf("GET", "POST"), "/{index}/_count", obj("query" to EsQuery)),
                    Endpoint(listOf("GET", "POST"), "/{index}/_validate/query", obj("query" to EsQuery)),
                    Endpoint(listOf("GET", "POST"), "/{index}/_explain/{id}", obj("query" to EsQuery)),
                    Endpoint(listOf("GET", "POST"), "/{index}/_mget", obj("ids" to list, "docs" to list)),
                    Endpoint(listOf("POST"), "/{index}/_delete_by_query", obj("query" to EsQuery, "max_docs" to scalar)),
                    Endpoint(listOf("POST"), "/{index}/_update_by_query", obj("query" to EsQuery, "script" to obj("source" to scalar, "params" to anyObject, "lang" to scalar), "max_docs" to scalar)),
                    Endpoint(listOf("POST"), "/{index}/_doc", EsDocument),
                    Endpoint(listOf("GET", "PUT", "POST", "DELETE", "HEAD"), "/{index}/_doc/{id}", EsDocument),
                    Endpoint(listOf("PUT", "POST"), "/{index}/_create/{id}", EsDocument),
                    Endpoint(listOf("POST"), "/{index}/_update/{id}", obj("doc" to EsDocument, "upsert" to EsDocument, "doc_as_upsert" to bool, "script" to obj("source" to scalar, "params" to anyObject))),
                    Endpoint(listOf("GET"), "/{index}/_source/{id}"),
                    Endpoint(listOf("GET", "PUT"), "/{index}/_mapping", EsMapping),
                    Endpoint(listOf("GET", "PUT"), "/{index}/_settings", obj("index" to obj("number_of_replicas" to scalar, "refresh_interval" to scalar, "max_result_window" to scalar))),
                    Endpoint(listOf("PUT"), "/{index}", obj("settings" to obj("number_of_shards" to scalar, "number_of_replicas" to scalar, "refresh_interval" to scalar, "analysis" to anyObject), "mappings" to EsMapping, "aliases" to anyObject)),
                    Endpoint(listOf("GET", "DELETE", "HEAD"), "/{index}"),
                    Endpoint(listOf("GET"), "/{index}/_stats"),
                    Endpoint(listOf("GET"), "/{index}/_alias"),
                    Endpoint(listOf("POST"), "/{index}/_refresh"),
                    Endpoint(listOf("POST"), "/{index}/_flush"),
                    Endpoint(listOf("POST"), "/{index}/_forcemerge"),
                    Endpoint(listOf("POST"), "/{index}/_open"),
                    Endpoint(listOf("POST"), "/{index}/_close"),
                    Endpoint(listOf("POST"), "/{index}/_bulk"),
                    Endpoint(listOf("GET", "POST"), "/{index}/_analyze", obj("analyzer" to scalar, "text" to scalar, "field" to field)),
                    Endpoint(listOf("POST", "GET"), "/_sql", obj("query" to scalar, "fetch_size" to scalar, "filter" to EsQuery, "time_zone" to scalar, "params" to list)),
                    Endpoint(listOf("POST"), "/_plugins/_sql", obj("query" to scalar)),
                    Endpoint(listOf("POST"), "/_plugins/_ppl", obj("query" to scalar)),
                    Endpoint(listOf("POST"), "/_reindex", obj("source" to obj("index" to container, "query" to EsQuery), "dest" to obj("index" to container), "max_docs" to scalar)),
                    Endpoint(listOf("POST"), "/_aliases", obj("actions" to list)),
                    Endpoint(listOf("POST"), "/_bulk"),
                    Endpoint(listOf("GET", "POST"), "/_mget", obj("docs" to list)),
                    Endpoint(listOf("GET", "POST"), "/_analyze", obj("analyzer" to scalar, "text" to scalar, "tokenizer" to scalar)),
                    Endpoint(listOf("POST"), "/_search/scroll", obj("scroll" to scalar, "scroll_id" to scalar)),
                ) +
                    get(
                        "/_cat/indices", "/_cat/aliases", "/_cat/health", "/_cat/nodes", "/_cat/shards", "/_cat/count", "/_cat/allocation", "/_cat/thread_pool",
                        "/_cat/plugins", "/_cat/templates", "/_cat/segments", "/_cat/recovery", "/_cat/indices/{index}", "/_cat/count/{index}", "/_cluster/health",
                        "/_cluster/state", "/_cluster/settings", "/_cluster/stats", "/_cluster/allocation/explain", "/_nodes", "/_nodes/stats", "/_tasks", "/_alias",
                        "/_mapping", "/_settings", "/_stats", "/_index_template", "/_data_stream", "/_snapshot", "/_ingest/pipeline", "/_ilm/policy", "/_license",
                        "/_security/_authenticate", "/",
                    ),
                containerPlaceholders = setOf("index"),
            )

        // endregion

        // region Solr

        private val Solr =
            HttpEndpoints(
                listOf(
                    Endpoint(
                        listOf("GET", "POST"),
                        "/solr/{collection}/select",
                        obj(
                            "query" to scalar, "filter" to list, "limit" to scalar, "offset" to scalar, "sort" to scalar, "fields" to fieldList,
                            "facet" to obj(any = obj("type" to choice("terms", "range", "query"), "field" to field, "limit" to scalar, "mincount" to scalar, "sort" to scalar)),
                            "params" to anyObject, "queries" to anyObject,
                        ),
                    ),
                    Endpoint(listOf("GET", "POST"), "/solr/{collection}/query", obj("query" to scalar, "filter" to list, "limit" to scalar, "offset" to scalar, "sort" to scalar, "fields" to fieldList, "params" to anyObject)),
                    Endpoint(listOf("POST"), "/solr/{collection}/update", JsonNode(Shape.Array, element = obj(fields = scalar))),
                    Endpoint(listOf("POST"), "/solr/{collection}/update/json/docs", obj(fields = scalar)),
                    Endpoint(listOf("GET", "POST"), "/solr/{collection}/sql"),
                    Endpoint(listOf("GET", "POST"), "/solr/{collection}/export"),
                    Endpoint(listOf("GET", "POST"), "/solr/{collection}/terms"),
                    Endpoint(listOf("GET", "POST"), "/solr/{collection}/suggest"),
                    Endpoint(listOf("GET", "POST"), "/solr/{collection}/schema"),
                ) +
                    get(
                        "/solr/{collection}/get", "/solr/{collection}/schema/fields", "/solr/{collection}/schema/fieldtypes", "/solr/{collection}/config",
                        "/solr/{collection}/admin/ping", "/solr/{collection}/admin/luke", "/solr/admin/collections?action=LIST", "/solr/admin/collections?action=CLUSTERSTATUS",
                        "/solr/admin/cores?action=STATUS", "/solr/admin/info/system",
                    ),
                containerPlaceholders = setOf("collection"),
            )

        // endregion

        // region Meilisearch

        private val MeiliSearch =
            obj(
                "q" to scalar, "filter" to scalar, "limit" to scalar, "offset" to scalar, "hitsPerPage" to scalar, "page" to scalar, "sort" to fieldList,
                "facets" to fieldList, "attributesToRetrieve" to fieldList, "attributesToHighlight" to fieldList, "attributesToCrop" to fieldList,
                "attributesToSearchOn" to fieldList, "cropLength" to scalar, "highlightPreTag" to scalar, "highlightPostTag" to scalar, "showMatchesPosition" to bool,
                "showRankingScore" to bool, "showRankingScoreDetails" to bool, "rankingScoreThreshold" to scalar, "matchingStrategy" to choice("last", "all", "frequency"),
                "distinct" to field, "hybrid" to obj("semanticRatio" to scalar, "embedder" to scalar), "vector" to list, "retrieveVectors" to bool,
            )
        private val MeiliSettings =
            obj(
                "filterableAttributes" to fieldList, "sortableAttributes" to fieldList, "searchableAttributes" to fieldList, "displayedAttributes" to fieldList,
                "rankingRules" to list, "distinctAttribute" to field, "stopWords" to list, "synonyms" to anyObject, "typoTolerance" to anyObject,
                "pagination" to obj("maxTotalHits" to scalar), "faceting" to obj("maxValuesPerFacet" to scalar),
            )

        private val Meilisearch =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("POST", "GET"), "/indexes/{index}/search", MeiliSearch),
                    Endpoint(listOf("POST"), "/indexes/{index}/documents/fetch", obj("fields" to fieldList, "filter" to scalar, "limit" to scalar, "offset" to scalar)),
                    Endpoint(listOf("POST"), "/indexes/{index}/facet-search", obj("facetName" to field, "facetQuery" to scalar, "q" to scalar, "filter" to scalar)),
                    Endpoint(listOf("POST"), "/indexes/{index}/similar", obj("id" to scalar, "embedder" to scalar, "filter" to scalar, "limit" to scalar, "attributesToRetrieve" to fieldList)),
                    Endpoint(listOf("GET", "POST", "PUT", "DELETE"), "/indexes/{index}/documents", JsonNode(Shape.Array, element = obj(fields = scalar))),
                    Endpoint(listOf("GET", "DELETE"), "/indexes/{index}/documents/{id}"),
                    Endpoint(listOf("GET", "PATCH", "DELETE"), "/indexes/{index}/settings", MeiliSettings),
                    Endpoint(listOf("GET", "PATCH", "DELETE"), "/indexes/{index}", obj("primaryKey" to field)),
                    Endpoint(listOf("GET", "POST"), "/indexes", obj("uid" to scalar, "primaryKey" to scalar)),
                    Endpoint(listOf("POST"), "/multi-search", obj("queries" to many(obj("indexUid" to container, *MeiliSearch.keys.map { it.key to it.value }.toTypedArray())))),
                ) +
                    listOf("filterable-attributes", "sortable-attributes", "searchable-attributes", "displayed-attributes", "ranking-rules", "synonyms", "stop-words", "typo-tolerance", "faceting", "pagination", "distinct-attribute")
                        .map { Endpoint(listOf("GET", "PUT", "DELETE"), "/indexes/{index}/settings/$it") } +
                    get("/indexes/{index}/stats", "/stats", "/health", "/version", "/tasks", "/keys", "/experimental-features"),
                containerPlaceholders = setOf("index"),
            )

        // endregion

        // region CouchDB

        private val Mango: JsonNode = obj()

        init {
            val mangoList = JsonNode(Shape.Array, element = Mango)
            val condition =
                obj(
                    "\$eq" to scalar, "\$ne" to scalar, "\$gt" to scalar, "\$gte" to scalar, "\$lt" to scalar, "\$lte" to scalar, "\$exists" to bool,
                    "\$type" to choice("null", "boolean", "number", "string", "array", "object"), "\$in" to list, "\$nin" to list, "\$size" to scalar, "\$mod" to list,
                    "\$regex" to scalar, "\$all" to list, "\$elemMatch" to Mango, "\$allMatch" to Mango, "\$not" to Mango, shape = Shape.Scalar,
                )
            Mango.keys.putAll(linkedMapOf("\$and" to mangoList, "\$or" to mangoList, "\$nor" to mangoList, "\$not" to Mango))
            Mango.fieldKeys = condition
        }

        private val CouchFind =
            obj(
                "selector" to Mango, "fields" to fieldList, "sort" to JsonNode(Shape.Array, element = JsonNode(fieldKeys = choice("asc", "desc"), fieldValues = true)),
                "limit" to scalar, "skip" to scalar, "use_index" to scalar, "bookmark" to scalar, "execution_stats" to bool, "conflicts" to bool, "r" to scalar,
            )
        private val CouchDocument = obj("_id" to scalar, "_rev" to scalar, "_deleted" to bool, fields = scalar)

        private val CouchDB =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("POST"), "/{db}/_find", CouchFind),
                    Endpoint(listOf("POST"), "/{db}/_explain", CouchFind),
                    Endpoint(listOf("GET", "POST"), "/{db}/_all_docs", obj("keys" to list, "include_docs" to bool, "limit" to scalar, "skip" to scalar, "descending" to bool)),
                    Endpoint(listOf("GET", "POST", "DELETE"), "/{db}/_index", obj("index" to obj("fields" to fieldList), "name" to scalar, "type" to choice("json", "text"), "ddoc" to scalar)),
                    Endpoint(listOf("POST"), "/{db}/_bulk_docs", obj("docs" to JsonNode(Shape.Array, element = CouchDocument), "new_edits" to bool)),
                    Endpoint(listOf("POST"), "/{db}/_bulk_get", obj("docs" to list)),
                    Endpoint(listOf("GET", "POST"), "/{db}/_changes", obj("doc_ids" to list, "selector" to Mango)),
                    Endpoint(listOf("GET", "POST"), "/{db}/_design/{ddoc}/_view/{view}", obj("keys" to list, "include_docs" to bool, "limit" to scalar, "reduce" to bool, "group" to bool)),
                    Endpoint(listOf("GET", "PUT", "DELETE"), "/{db}/_design/{ddoc}"),
                    Endpoint(listOf("GET"), "/{db}/_design/{ddoc}/_info"),
                    Endpoint(listOf("GET", "PUT", "DELETE", "HEAD"), "/{db}/{docid}", CouchDocument),
                    Endpoint(listOf("POST"), "/{db}", CouchDocument),
                    Endpoint(listOf("GET", "PUT", "DELETE", "HEAD"), "/{db}"),
                    Endpoint(listOf("POST"), "/{db}/_compact"),
                    Endpoint(listOf("GET", "PUT"), "/{db}/_security", obj("admins" to anyObject, "members" to anyObject)),
                    Endpoint(listOf("POST"), "/_dbs_info", obj("keys" to JsonNode(Shape.Array, element = container))),
                ) +
                    get(
                        "/{db}/_design_docs", "/{db}/_local_docs", "/_all_dbs", "/_up", "/_active_tasks", "/_membership", "/_scheduler/jobs", "/_node/_local/_stats",
                        "/_node/_local/_config", "/_session", "/_uuids", "/",
                    ),
                containerPlaceholders = setOf("db"),
            )

        // endregion

        // region Weaviate

        private val Weaviate =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("GET", "PUT", "DELETE"), "/v1/schema/{class}"),
                    Endpoint(listOf("POST"), "/v1/schema/{class}/properties", obj("name" to scalar, "dataType" to list, "description" to scalar)),
                    Endpoint(listOf("GET", "POST"), "/v1/schema", obj("class" to scalar, "properties" to list, "vectorizer" to scalar, "description" to scalar)),
                    Endpoint(listOf("GET", "PUT", "PATCH", "DELETE", "HEAD"), "/v1/objects/{class}/{id}", obj("class" to container, "properties" to anyObject)),
                    Endpoint(listOf("GET", "POST"), "/v1/objects", obj("class" to container, "properties" to anyObject, "id" to scalar, "vector" to list)),
                    Endpoint(listOf("POST", "DELETE"), "/v1/batch/objects", obj("objects" to list, "match" to anyObject)),
                    Endpoint(listOf("POST"), "/v1/graphql", obj("query" to scalar, "variables" to anyObject)),
                ) + get("/v1/schema/{class}/shards", "/v1/meta", "/v1/nodes", "/v1/cluster/statistics", "/v1/.well-known/ready", "/v1/.well-known/live", "/v1/backups"),
                containerPlaceholders = setOf("class"),
                other = WeaviateGraphQl::complete,
            )

        // endregion

        // region The rest: paths only

        private val HBase =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("GET", "PUT", "POST", "DELETE"), "/{table}/schema"),
                    Endpoint(listOf("PUT", "POST"), "/{table}/scanner", obj("batch" to scalar, "startRow" to scalar, "endRow" to scalar, "column" to list, "filter" to scalar, "maxVersions" to scalar)),
                    Endpoint(listOf("GET", "PUT", "POST", "DELETE"), "/{table}/{row}"),
                    Endpoint(listOf("GET", "PUT", "POST", "DELETE"), "/{table}/{row}/{column}"),
                ) + get("/{table}/regions", "/{table}/*", "/version/cluster", "/status/cluster", "/namespaces", "/version", "/"),
                containerPlaceholders = setOf("table"),
            )

        private val Consul =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("GET", "PUT", "DELETE"), "/v1/kv/{key}"),
                    Endpoint(listOf("PUT"), "/v1/txn"),
                ) +
                    get(
                        "/v1/kv/", "/v1/catalog/services", "/v1/catalog/nodes", "/v1/catalog/datacenters", "/v1/catalog/service/{service}", "/v1/health/service/{service}",
                        "/v1/health/state/any", "/v1/health/node/{node}", "/v1/agent/self", "/v1/agent/members", "/v1/agent/services", "/v1/agent/checks",
                        "/v1/status/leader", "/v1/status/peers", "/v1/session/list", "/v1/acl/tokens", "/v1/operator/raft/configuration",
                    ),
                containerPlaceholders = emptySet(),
            )

        private val QdrantFilter: JsonNode =
            obj().also { filter ->
                val condition = obj("key" to scalar, "match" to obj("value" to scalar, "any" to list, "text" to scalar), "range" to obj("gt" to scalar, "gte" to scalar, "lt" to scalar, "lte" to scalar), "is_empty" to anyObject, "is_null" to anyObject, "has_id" to list)
                val conditions = JsonNode(Shape.Array, element = condition)
                filter.keys.putAll(linkedMapOf("must" to conditions, "should" to conditions, "must_not" to conditions))
            }

        private val Qdrant =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("POST"), "/collections/{collection}/points/scroll", obj("limit" to scalar, "offset" to scalar, "filter" to QdrantFilter, "with_payload" to bool, "with_vector" to bool, "order_by" to scalar)),
                    Endpoint(listOf("POST"), "/collections/{collection}/points/search", obj("vector" to list, "limit" to scalar, "filter" to QdrantFilter, "with_payload" to bool, "score_threshold" to scalar)),
                    Endpoint(listOf("POST"), "/collections/{collection}/points/query", obj("query" to scalar, "limit" to scalar, "filter" to QdrantFilter, "with_payload" to bool, "using" to scalar)),
                    Endpoint(listOf("POST"), "/collections/{collection}/points/count", obj("filter" to QdrantFilter, "exact" to bool)),
                    Endpoint(listOf("POST"), "/collections/{collection}/points", obj("ids" to list, "with_payload" to bool, "with_vector" to bool)),
                    Endpoint(listOf("PUT"), "/collections/{collection}/points", obj("points" to list)),
                    Endpoint(listOf("POST"), "/collections/{collection}/points/delete", obj("points" to list, "filter" to QdrantFilter)),
                    Endpoint(listOf("POST", "PUT"), "/collections/{collection}/points/payload", obj("payload" to anyObject, "points" to list, "filter" to QdrantFilter)),
                    Endpoint(listOf("GET", "PUT", "PATCH", "DELETE"), "/collections/{collection}"),
                    Endpoint(listOf("PUT"), "/collections/{collection}/index", obj("field_name" to scalar, "field_schema" to choice("keyword", "integer", "float", "bool", "geo", "datetime", "text", "uuid"))),
                ) + get("/collections", "/collections/{collection}/exists", "/collections/{collection}/snapshots", "/collections/{collection}/aliases", "/aliases", "/cluster", "/telemetry", "/"),
                containerPlaceholders = setOf("collection"),
            )

        private val Milvus =
            HttpEndpoints(
                listOf("collections/list", "collections/describe", "collections/get_stats", "collections/has", "collections/load", "collections/get_load_state", "collections/release", "collections/drop", "collections/create", "entities/query", "entities/search", "entities/get", "entities/insert", "entities/upsert", "entities/delete", "indexes/list", "indexes/describe", "partitions/list", "aliases/list", "databases/list")
                    .map { Endpoint(listOf("POST"), "/v2/vectordb/$it", obj("collectionName" to container, "dbName" to scalar, "filter" to scalar, "limit" to scalar, "offset" to scalar, "outputFields" to list, "data" to list, "annsField" to scalar, "id" to list)) },
                containerPlaceholders = emptySet(),
                methods = listOf("POST"),
            )

        private val RabbitMQ =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("POST"), "/api/queues/{vhost}/{queue}/get", obj("count" to scalar, "ackmode" to choice("ack_requeue_true", "ack_requeue_false", "reject_requeue_true", "reject_requeue_false"), "encoding" to choice("auto", "base64"), "truncate" to scalar)),
                    Endpoint(listOf("POST"), "/api/exchanges/{vhost}/{exchange}/publish", obj("properties" to anyObject, "routing_key" to scalar, "payload" to scalar, "payload_encoding" to choice("string", "base64"))),
                    Endpoint(listOf("GET", "PUT", "DELETE"), "/api/queues/{vhost}/{queue}", obj("durable" to bool, "auto_delete" to bool, "arguments" to anyObject)),
                    Endpoint(listOf("DELETE"), "/api/queues/{vhost}/{queue}/contents"),
                    Endpoint(listOf("GET", "PUT", "DELETE"), "/api/exchanges/{vhost}/{exchange}", obj("type" to choice("direct", "fanout", "topic", "headers"), "durable" to bool, "auto_delete" to bool)),
                ) +
                    get(
                        "/api/overview", "/api/queues", "/api/queues/{vhost}", "/api/exchanges", "/api/exchanges/{vhost}", "/api/bindings", "/api/bindings/{vhost}", "/api/connections",
                        "/api/channels", "/api/consumers", "/api/vhosts", "/api/nodes", "/api/users", "/api/permissions", "/api/definitions", "/api/health/checks/alarms", "/api/whoami",
                    ),
                containerPlaceholders = setOf("vhost"),
            )

        private val Chroma =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("POST"), "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/get", obj("ids" to list, "where" to anyObject, "where_document" to anyObject, "limit" to scalar, "offset" to scalar, "include" to list)),
                    Endpoint(listOf("POST"), "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/query", obj("query_embeddings" to list, "query_texts" to list, "n_results" to scalar, "where" to anyObject, "include" to list)),
                    Endpoint(listOf("POST"), "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/add", obj("ids" to list, "documents" to list, "metadatas" to list, "embeddings" to list)),
                    Endpoint(listOf("POST"), "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/delete", obj("ids" to list, "where" to anyObject)),
                    Endpoint(listOf("GET", "POST"), "/api/v2/tenants/{tenant}/databases/{database}/collections", obj("name" to scalar, "metadata" to anyObject)),
                ) +
                    get(
                        "/api/v2/heartbeat", "/api/v2/version", "/api/v2/auth/identity", "/api/v2/tenants/{tenant}/databases",
                        "/api/v2/tenants/{tenant}/databases/{database}/collections/{collection}/count", "/api/v2/tenants/{tenant}/databases/{database}/collections_count",
                    ),
                containerPlaceholders = emptySet(),
            )

        private val Nacos =
            HttpEndpoints(
                listOf(
                    Endpoint(listOf("GET", "POST", "DELETE"), "/v1/cs/configs"),
                    Endpoint(listOf("GET", "POST", "PUT", "DELETE"), "/v1/ns/instance"),
                    Endpoint(listOf("GET", "POST", "PUT", "DELETE"), "/v1/ns/service"),
                    Endpoint(listOf("GET", "POST", "PUT", "DELETE"), "/v1/console/namespaces"),
                ) + get("/v1/cs/history", "/v1/ns/service/list", "/v1/ns/instance/list", "/v1/ns/operator/servers", "/v1/ns/operator/metrics", "/v1/core/cluster/nodes"),
                containerPlaceholders = emptySet(),
            )

        // endregion
    }
}

/**
 * Weaviate's GraphQL: `Get`, `Aggregate` and `Explore` at the top, the classes under them, a class's properties (and
 * `_additional`) under it, and a class's search arguments in its parentheses.
 */
internal object WeaviateGraphQl {
    fun complete(text: String, caret: Int, catalog: ConsoleCatalog): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val tokens = lex(text.substring(0, at), doubleQuotedStrings = true)
        if (tokens.any { it.encloses(at, text) }) return CompletionResult.empty(at)
        val start = Completions.wordStart(text, at)
        val end = Completions.wordEnd(text, at)
        val prefix = text.substring(start, at)
        val stack = ArrayList<String>()
        var parens = 0
        var lastWord = ""
        for (t in tokens.filter { it.end <= start && it.type != Lexeme.Type.Comment }) {
            when (t.text) {
                "(" -> parens++
                ")" -> parens--
                "{" -> if (parens == 0) stack += lastWord else stack += ""
                "}" -> stack.removeLastOrNull()
                else -> if (t.type == Lexeme.Type.Word && parens == 0) lastWord = t.text
            }
        }
        fun result(items: List<CompletionItem>) = CompletionResult(start, end, prefix, Completions.rank(items, prefix))
        if (parens > 0) {
            if (stack.size == 2 && stack[1] == "Get") return result(GetArguments.map { CompletionItem(it, "$it: ", CompletionKind.Keyword) })
            return result(emptyList())
        }
        val classes = { catalog.containers(emptyList()).orEmpty().map { CompletionItem(it, "$it ", CompletionKind.Table, "class") } }
        return result(
            when {
                stack.size == 1 -> Completions.keywords(listOf("Get", "Aggregate", "Explore"))
                stack.size == 2 && (stack[1] == "Get" || stack[1] == "Aggregate") -> classes()
                stack.size == 3 && stack[1] == "Get" ->
                    catalog.fields(listOf(stack[2])).orEmpty().map { CompletionItem(it, it, CompletionKind.Column) } + CompletionItem("_additional", "_additional { id }", CompletionKind.Keyword, caretOffset = 14)
                stack.size == 3 && stack[1] == "Aggregate" -> catalog.fields(listOf(stack[2])).orEmpty().map { CompletionItem(it, it, CompletionKind.Column) } + CompletionItem("meta", "meta { count }", CompletionKind.Keyword)
                stack.lastOrNull() == "_additional" -> Additional.map { CompletionItem(it, it, CompletionKind.Keyword) }
                stack.size == 2 && stack[1] == "Explore" -> listOf("beacon", "certainty", "className", "distance").map { CompletionItem(it, it, CompletionKind.Keyword) }
                else -> emptyList()
            },
        )
    }

    private val GetArguments = listOf("limit", "offset", "where", "nearText", "nearVector", "nearObject", "bm25", "hybrid", "sort", "after", "autocut", "groupBy")
    private val Additional = listOf("id", "distance", "certainty", "score", "explainScore", "vector", "creationTimeUnix", "lastUpdateTimeUnix")
}
