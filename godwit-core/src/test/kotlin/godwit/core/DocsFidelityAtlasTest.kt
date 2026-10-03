package godwit.core

import godwit.core.docs.Output
import godwit.core.docs.Runner
import godwit.core.docs.quotedBlocks
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty

/** The quotes of [DocsFidelityTest] whose scenario needs Atlas Search, on the Atlas local image. */
@Tags("Atlas")
class DocsFidelityAtlasTest : StringSpec() {
    init {
        val atlas = quotedBlocks.filter { (it.check as? Output)?.runner == Runner.ATLAS }

        "the docs quote outputs of scenarios that need Atlas Search" {
            atlas.shouldNotBeEmpty()
        }

        for (quoted in atlas) {
            val check = quoted.check as Output
            "$quoted is what ${check.scenario} produces" {
                check.verify(blockOf(quoted.doc, quoted.ordinal).lines, check.scenario!!.produced)
            }
        }
    }
}
