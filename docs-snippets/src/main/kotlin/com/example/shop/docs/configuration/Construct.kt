package com.example.shop.docs.configuration

// region: construct
import com.example.shop.ShopConfig
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit

/** One Godwit for one database, built from the client that the app's services use. */
fun shopGodwit(client: MongoClient, config: ShopConfig): Godwit = Godwit(client, config.mongo.database)
// endregion
