package godwit.core.fixtures

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.nin
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import godwit.core.Migration
import godwit.core.repeatable
import org.bson.Document

/** The countries the shop ships to at revision `2026-10-01`. */
val OCTOBER_COUNTRIES = listOf("GB" to "United Kingdom", "IE" to "Ireland", "FR" to "France", "DE" to "Germany")

/** The countries the shop ships to at revision `2026-11-15`: October's and Spain. */
val NOVEMBER_COUNTRIES = OCTOBER_COUNTRIES + ("ES" to "Spain")

/**
 * The shop's `reference-countries` at [revision]: in one transaction, it upserts every country of [countries], deletes
 * every other one and counts the deletions as `countriesRemoved`. It also increments the probe
 * `{_id: "reference-countries", n}` in `probes`, which counts the runs that committed, whatever the driver retried.
 */
fun referenceCountries(revision: String, countries: List<Pair<String, String>> = OCTOBER_COUNTRIES): Migration =
    repeatable("reference-countries", revision).inTransaction {
        collection("probes").updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
        val stored = collection("countries")
        countries.forEach { (code, name) ->
            val country = Document("_id", code).append("name", name)
            stored.replaceOne(session, eq("_id", code), country, ReplaceOptions().upsert(true))
        }
        count("countriesRemoved", stored.deleteMany(session, nin("_id", countries.map { it.first })).deletedCount)
    }

/** The collection a command works on: the string value of its first key, such as `godwit-lock` in `{update: ...}`. */
val RecordedCommand.collection: String?
    get() = command[name]?.takeIf { it.isString }?.asString()?.value

/**
 * A renewal of the lock: an update of `godwit-lock` fenced on `releasedAt` being absent. Only the heartbeat sends one,
 * once per `heartbeat` (20 s by default) while a run holds the lock.
 */
val RecordedCommand.isRenewal: Boolean
    get() = name == "update" && collection == "godwit-lock" &&
        command.getArray("updates")[0].asDocument().getDocument("q").containsKey("releasedAt")

/** How a start used the lock, from the commands its client sent to `godwit-lock`: `acquire`, `release`, `read`. */
fun List<RecordedCommand>.lockOperations(): List<String> = filter { it.collection == "godwit-lock" && !it.isRenewal }
    .map {
        when (it.name) {
            "findAndModify" -> "acquire"
            "update" -> "release"
            "find" -> "read"
            else -> it.name
        }
    }
