package org.grails.plugins.elasticsearch

import grails.plugin.spock.IntegrationSpec
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequestBuilder
import org.elasticsearch.client.AdminClient
import org.elasticsearch.client.ClusterAdminClient
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.IndexMetaData
import org.elasticsearch.cluster.metadata.MappingMetaData
import test.Building
import test.GeoPoint
import test.Product

class ElasticSearchServiceIntegrationSpec extends IntegrationSpec {

    def elasticSearchService
    def elasticSearchAdminService
    def elasticSearchHelper

    def setupSpec() {
        [
            [lat: 48.13, lon: 11.60, name: '81667'],
            [lat: 48.19, lon: 11.65, name: '85774'],
            [lat: 47.98, lon: 10.18, name: '87700']
        ].each {
            def geoPoint = new GeoPoint(lat: it.lat, lon: it.lon).save()
            new Building(name: "postalCode${it.name}", location: geoPoint).save()
        }
    }

    /*
     * This test class doesn't delete any ElasticSearch indices, because that would also delete the mapping.
     * Be aware of this when indexing new objects.
     */

    def cleanupSpec() {
        def dataFolder = new File('data')
        if (dataFolder.isDirectory()) {
            dataFolder.deleteDir()
        }
    }

    def "Index and un-index a domain object"() {
        given:
        def product = new Product(name: "myTestProduct")
        product.save(failOnError: true)

        when:
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh() // Ensure the latest operations have been exposed on the ES instance

        and:
        elasticSearchService.search("myTestProduct", [indices: Product, types: Product]).total == 1

        then:
        elasticSearchService.unindex(product)
        elasticSearchAdminService.refresh()

        and:
        elasticSearchService.search("myTestProduct", [indices: Product, types: Product]).total == 0
    }

    def "Indexing the same object multiple times updates the corresponding ES entry"() {
        given:
        def product = new Product(name: "myTestProduct")
        product.save(failOnError: true)

        when:
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        then:
        elasticSearchService.search("myTestProduct", [indices: Product, types: Product]).total == 1

        when:
        product.name = "newProductName"
        product.save(failOnError: true)
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        then:
        elasticSearchService.search("myTestProduct", [indices: Product, types: Product]).total == 0

        and:
        def result = elasticSearchService.search(product.name, [indices: Product, types: Product])
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == product.name

    }

    void "a date value should be marshalled and de-marshalled correctly"() {
        def date = new Date()
        given:
        def product = new Product(
            name: 'product with date value',
            date: date
        ).save(failOnError: true)

        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search(product.name, [indices: Product, types: Product])

        then:
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == product.name
        searchResults[0].date == product.date
    }

    void "a geo point location is marshalled and de-marshalled correctly"() {
        given:
        def location = new GeoPoint(
            lat: 53.00,
            lon: 10.00
        ).save(failOnError: true)

        def building = new Building(
            name: 'WatchTower',
            location: location
        ).save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search('WatchTower', [indices: Building, types: Building])

        then:
        elasticSearchHelper.elasticSearchClient.admin().indices()

        result.total == 1
        List<Building> searchResults = result.searchResults
        searchResults[0].location == location
    }

    void "a geo point is mapped correctly"() {

        given:
        def location = new GeoPoint(
            lat: 53.00,
            lon: 10.00
        ).save(failOnError: true)

        def building = new Building(
            location: location
        ).save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        expect:
        def mapping = getFieldMappingMetaData("test", "building").sourceAsMap
        mapping.(properties).location.type == 'geo_point'
    }

    private MappingMetaData getFieldMappingMetaData(String indexName, String typeName) {
        AdminClient admin = elasticSearchHelper.elasticSearchClient.admin()
        ClusterAdminClient cluster = admin.cluster()

        ClusterStateRequestBuilder indices = cluster.prepareState().setFilterIndices(indexName)
        ClusterState clusterState = indices.execute().actionGet().state
        IndexMetaData indexMetaData = clusterState.metaData.index(indexName)
        return indexMetaData.mapping(typeName)
    }

    void "search with geo distance filter"() {
        given: "a building with a geo point location"
        GeoPoint geoPoint = new GeoPoint(
            lat: 50.1,
            lon: 13.3
        ).save(failOnError: true)

        def building = new Building(
            name: 'Test Product',
            location: geoPoint
        ).save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        when: "a geo distance filter search is performed"

        Map params = [indices: Building, types: Building]
        Closure query = null
        def location = '50, 13'

        Closure filter = {
            'geo_distance'(
                'distance': '50km',
                'location': location
            )
        }

        def result = elasticSearchService.search(params, query, filter)

        then: "the building should be found"
        1 == result.total
        List<Building> searchResults = result.searchResults
        searchResults[0].id == building.id
    }

    void "searching with filtered query"() {
        given: "some products"
        def wurstProduct = new Product(name: "wurst", price: 2.00)
        wurstProduct.save(failOnError: true)

        def hansProduct = new Product(name: 'hans', price: 0.5)
        hansProduct.save(failOnError: true)

        def fooProduct = new Product(name: 'foo', price: 5.0)
        fooProduct.save(failOnError: true)

        elasticSearchService.index(wurstProduct, hansProduct, fooProduct)
        elasticSearchAdminService.refresh()

        when: "that a range filter find the product"
        def result = elasticSearchService.search(null as Closure, {
            range {
                "price"(gte: 1.99, lte: 2.3)
            }
        })

        then: "the result should be product 'wurst'"
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == wurstProduct.name
    }

    void "a geo distance search finds geo points at varying distances"() {
        def buildings = Building.list()
        buildings.each {
            it.delete()
        }

        when: 'a geo distance search is performed'
        Map params = [indices: Building, types: Building]
        Closure query = null
        def location = [lat: 48.141, lon: 11.57]

        Closure filter = {
            geo_distance(
                'distance': distance,
                'location': location
            )
        }
        def result = elasticSearchService.search(params, query, filter)

        then: 'all geo points in the search radius are found'
        List<Building> searchResults = result.searchResults

        (postalCodesFound.empty && searchResults.empty) || searchResults.each { searchResult ->
            searchResult.name in postalCodesFound
        }

        where:
        distance || postalCodesFound
        '1km'     | []
        '5km'     | ['81667']
        '20km'    | ['81667', '85774']
        '1000km'  | ['81667', '85774', '87700']
    }
}
