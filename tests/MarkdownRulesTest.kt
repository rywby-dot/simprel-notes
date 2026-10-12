package com.simprel.notes

import org.commonmark.parser.Parser
import org.commonmark.node.*
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough

fun main() {
    val parser = Parser.builder().extensions(listOf(StrikethroughExtension.create())).build()
    fun nodes(source: String): List<Node> {
        val result = mutableListOf<Node>()
        fun walk(node: Node) {
            result.add(node)
            var child = node.firstChild
            while (child != null) { walk(child); child = child.next }
        }
        walk(parser.parse(source)); return result
    }
    check(nodes("***nested***").any { it is StrongEmphasis })
    check(nodes("***nested***").any { it is Emphasis })
    check(nodes("foo_bar_baz").none { it is Emphasis })
    check(nodes("\\*literal\\*").none { it is Emphasis })
    check(nodes("Title\n=====").filterIsInstance<Heading>().single().level == 1)
    check(nodes("## Title ##").filterIsInstance<Text>().single().literal == "Title")
    check(nodes("```kotlin\n**literal**\n```").filterIsInstance<FencedCodeBlock>().single().literal == "**literal**\n")
    check(nodes("    *literal*\n").any { it is IndentedCodeBlock })
    check(nodes("``a ` b``").filterIsInstance<Code>().single().literal == "a ` b")
    check(nodes("[text][ref]\n\n[ref]: https://example.com").filterIsInstance<Link>().single().destination == "https://example.com")
    check(nodes("<https://example.com>").any { it is Link })
    check(nodes("- first\n  - nested\n\n1. ordered").count { it is BulletList } == 2)
    check(nodes("- first\n  - nested\n\n1. ordered").any { it is OrderedList })
    check(nodes("> quote\n>\n> again").any { it is BlockQuote })
    check(nodes("~~deleted~~").any { it is Strikethrough })
    check(nodes("&amp; &#169;").filterIsInstance<Text>().single().literal == "& ©")
    check(nodes("---").any { it is ThematicBreak })
    check(nodes("first  \nsecond").any { it is HardLineBreak })
    println("PASS: CommonMark nesting, escapes, headings, code, reference/autolinks, nested/ordered lists, quotes, strike, entities, rules and breaks")
}
