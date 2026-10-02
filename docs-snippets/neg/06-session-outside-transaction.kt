// expect: Unresolved reference 'session'
// An outside step has no session.
package neg

import godwit.core.migration
import org.bson.Document

val noSession = migration("001-a").outsideTransaction { collection("customers").insertOne(session, Document()) }
