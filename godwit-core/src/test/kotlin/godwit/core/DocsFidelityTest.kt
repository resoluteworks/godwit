package godwit.core

import godwit.core.docs.CompilerError
import godwit.core.docs.FencedBlock
import godwit.core.docs.NotOutput
import godwit.core.docs.Output
import godwit.core.docs.Runner
import godwit.core.docs.fencedBlocks
import godwit.core.docs.inlineQuotes
import godwit.core.docs.negExpectation
import godwit.core.docs.nonKotlinBlocks
import godwit.core.docs.quotedBlocks
import godwit.core.docs.quotedInlines
import godwit.core.docs.repositoryRoot
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger(DocsFidelityTest::class.java)

/**
 * Every log line, history document, exception message and problem text that README.md and the Markdown files under docs/ quote is what the
 * implementation produces. docs/development is left out: it describes how godwit is built.
 *
 * The quotes are enumerated in godwit.core.docs.DocsQuotes: every fenced block other than kotlin, in doc order, and
 * every inline span of the prose that quotes an output, each with how it is checked. The first two tests hold that
 * list against the docs, so a block or span the docs add, remove or move fails here until the list says what it is.
 * Each other test runs the scenario behind one quoted output (godwit.core.docs: the docs' example shop, on a replica
 * set of its own) and compares the quote with what the implementation produced, ignoring run ids, owner tokens, times
 * and durations: log lines printed with the Logback configuration that configuration.md shows, exceptions as the head
 * of their stack trace, documents as stored (field order aside), problem lines as the exception lists them. A doc
 * change without the matching code change fails the quote's test.
 *
 * The scenarios that need Atlas Search run in DocsFidelityAtlasTest (`atlasTest`), and those that need the test kit's
 * SessionEscapeDetector in godwit-test's DocsFidelityTest. Compiler errors are the expectations of the `neg/` snippets
 * that scripts/neg-check.sh compiles.
 */
class DocsFidelityTest : StringSpec() {
    init {
        "every fenced block of the published docs other than kotlin is enumerated, in doc order" {
            val blocks = nonKotlinBlocks()
            blocks.map { "${it.doc} block ${it.ordinal}" } shouldContainExactly quotedBlocks.map { it.toString() }
            blocks.zip(quotedBlocks).forEach { (block, quoted) ->
                withClue(quoted) { block.lines.first().trim() shouldStartWith quoted.first }
            }
            log.info(
                "docs quotes blocks={} outputs={} compilerErrors={} notOutputs={} inline={}",
                quotedBlocks.size,
                quotedBlocks.count { it.check is Output },
                quotedBlocks.count { it.check == CompilerError },
                quotedBlocks.count { it.check is NotOutput },
                quotedInlines.size
            )
        }

        "every inline output the published docs' prose quotes is enumerated" {
            val spans = inlineQuotes().map { "${it.doc} `${it.text}`" }.distinct()
            spans.sorted() shouldContainExactly quotedInlines.map { it.toString() }.sorted()
        }

        "a block that is not an output says what it is" {
            quotedBlocks.map { it.check }.filterIsInstance<NotOutput>().forEach { it.reason.shouldNotBeBlank() }
        }

        for (quoted in quotedBlocks.filter { it.check == CompilerError }) {
            "$quoted is the compiler error its neg/ snippet expects" {
                val blocks = fencedBlocks(quoted.doc)
                val error = blocks.single { it.ordinal == quoted.ordinal }
                val code = blocks[blocks.indexOf(error) - 1]
                code.language shouldBe "kotlin"
                code.before shouldBe "This does not compile:"
                error.lines shouldBe listOf("e: ${negExpectation(code)}")
            }
        }

        for (quoted in quotedBlocks) {
            val check = quoted.check as? Output ?: continue
            if (check.runner != Runner.CORE) continue
            "$quoted is what ${check.scenario} produces" {
                val block = blockOf(quoted.doc, quoted.ordinal)
                log.info("checking {} line {} against scenario {}", quoted, block.line, check.scenario)
                check.verify(block.lines, check.scenario!!.produced)
            }
        }

        for (quoted in quotedInlines) {
            val check = quoted.check as Output
            "$quoted is what ${check.scenario} produces" {
                check.verify(listOf(quoted.text), check.scenario!!.produced)
            }
        }

        "consumer-smoke holds the README's example and test, each in a file of its own after the package line" {
            val readme = fencedBlocks("README.md").filter { it.language == "kotlin" }
            val smoke = File(repositoryRoot, "consumer-smoke/src").walk().filter { it.extension == "kt" }
                .map { it.readText().substringAfter("\n\n").trimEnd() }
                .toSet()
            readme.forEach { block -> withClue(block) { (block.text.trimEnd() in smoke) shouldBe true } }
            smoke.size shouldBe readme.size
        }
    }
}

/** The [ordinal]-th fenced block of [doc] other than kotlin. */
fun blockOf(doc: String, ordinal: Int): FencedBlock = fencedBlocks(doc).single { it.ordinal == ordinal }
