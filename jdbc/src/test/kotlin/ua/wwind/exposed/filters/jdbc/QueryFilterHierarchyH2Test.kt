package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.jdbc.Database

class QueryFilterHierarchyH2Test : QueryFilterHierarchyContract() {
    override fun connect() {
        Database.connect(
            url = "jdbc:h2:mem:hierarchy_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }
}
