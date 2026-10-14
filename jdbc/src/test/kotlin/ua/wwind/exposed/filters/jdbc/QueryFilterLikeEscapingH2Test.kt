package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.jdbc.Database

class QueryFilterLikeEscapingH2Test : QueryFilterLikeEscapingContract() {
    override val supportsJsonPath: Boolean = false

    override fun connect() {
        Database.connect(
            url = "jdbc:h2:mem:like_escaping_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }
}
