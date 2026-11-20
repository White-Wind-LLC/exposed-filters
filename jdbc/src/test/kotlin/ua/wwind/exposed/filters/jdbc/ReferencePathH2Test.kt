package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.jdbc.Database

class ReferencePathH2Test : ReferencePathContract() {
    override val rendersJson: Boolean = false

    override fun connect() {
        Database.connect(
            url = "jdbc:h2:mem:reference_paths_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }
}
