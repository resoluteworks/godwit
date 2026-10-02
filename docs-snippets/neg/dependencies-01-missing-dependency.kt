// expect: No value passed for parameter 'gateway'
// docs/dependencies.md: a list function whose migrations need a new service does not compile until the caller passes it.
package neg

import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.Migration

class ShopConfig(val mongo: MongoSettings)
class MongoSettings(val uri: String, val database: String)
class CustomerService
interface IdentityProvider
interface PaymentGateway

fun shopMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider,
    gateway: PaymentGateway
): List<Migration> = TODO()

fun start(config: ShopConfig, client: MongoClient, customers: CustomerService, identity: IdentityProvider) {
    Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))
}
