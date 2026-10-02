// expect: Unresolved reference 'outsideTransaction'
// At most one outside step, and it comes first.
package neg

import godwit.core.migration

val twiceOutside = migration("001-a").outsideTransaction { }.outsideTransaction { }
