package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.jdbc.Database

class PolymorphicPathH2Test : PolymorphicPathContract() {
    override fun connect() {
        Database.connect(
            url = "jdbc:h2:mem:polymorphic_paths_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }
}
