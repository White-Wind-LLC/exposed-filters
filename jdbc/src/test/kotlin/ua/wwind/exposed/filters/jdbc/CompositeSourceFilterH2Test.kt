package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.jdbc.Database

class CompositeSourceFilterH2Test : CompositeSourceFilterContract() {
    override fun connect() {
        Database.connect(
            url = "jdbc:h2:mem:composite_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }
}
