package com.example.shop.docs.adopting_an_existing_database

// region: rehearsal
import com.example.shop.ShopConfig
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.Origin

/** What the rehearsal on a restored copy of production recorded and ran. */
data class Rehearsal(val adopted: List<String>, val ran: List<String>, val readyForNextRelease: Boolean)

/**
 * Runs the adoption against [copyName], a restored copy of the production database. [identity] is a fake or a
 * sandbox: the outside steps of the pending migrations call it for real.
 */
fun rehearseAdoption(client: MongoClient, copyName: String, config: ShopConfig, identity: IdentityProvider): Rehearsal {
    val migrations = shopMigrations(config, CustomerService(client.getDatabase(copyName)), identity)
    val godwit = Godwit(client, copyName, GodwitConfig(adoptApplied = ::appliedBeforeGodwit))

    val report = godwit.migrate(migrations)

    return Rehearsal(
        adopted = report.recorded.filter { it.origin == Origin.ADOPTED }.map { it.id },
        ran = report.ran.map { it.id },
        readyForNextRelease = godwit.status(migrations).isUpToDate
    )
}
// endregion
