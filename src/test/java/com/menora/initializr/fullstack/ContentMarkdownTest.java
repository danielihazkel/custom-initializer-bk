package com.menora.initializr.fullstack;

import com.menora.initializr.fullstack.ContentMarkdown.Block;
import com.menora.initializr.fullstack.ContentMarkdown.BlockKind;
import com.menora.initializr.fullstack.ContentMarkdown.Run;
import com.menora.initializr.fullstack.ContentMarkdown.RunKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentMarkdownTest {

    @Test
    void readsBlocks() {
        List<Block> blocks = ContentMarkdown.parse("""
                # Title
                ## Sub
                A paragraph
                over two lines.

                - one
                * two
                1. first
                2) second
                > a quote
                > goes on
                ---
                Last.""");
        assertThat(blocks).extracting(Block::kind).containsExactly(BlockKind.HEADING, BlockKind.HEADING, BlockKind.PARAGRAPH,
                BlockKind.BULLETS, BlockKind.NUMBERS, BlockKind.CALLOUT, BlockKind.RULE, BlockKind.PARAGRAPH);
        assertThat(blocks.get(0).level()).isEqualTo(1);
        assertThat(blocks.get(1).level()).isEqualTo(2);
        assertThat(blocks.get(2).items().get(0)).containsExactly(new Run(RunKind.TEXT, "A paragraph over two lines.", null));
        assertThat(blocks.get(3).items()).hasSize(2);
        assertThat(blocks.get(4).items()).hasSize(2);
        assertThat(blocks.get(5).items().get(0)).containsExactly(new Run(RunKind.TEXT, "a quote goes on", null));
    }

    @Test
    void readsInlineRuns() {
        List<Run> runs = ContentMarkdown.inline("Plain **bold** *em* `code` [page](#/orders) [page2](page:help) [site](https://example.com)");
        assertThat(runs).containsExactly(
                new Run(RunKind.TEXT, "Plain ", null),
                new Run(RunKind.BOLD, "bold", null),
                new Run(RunKind.TEXT, " ", null),
                new Run(RunKind.EM, "em", null),
                new Run(RunKind.TEXT, " ", null),
                new Run(RunKind.CODE, "code", null),
                new Run(RunKind.TEXT, " ", null),
                new Run(RunKind.PAGE_LINK, "page", "orders"),
                new Run(RunKind.TEXT, " ", null),
                new Run(RunKind.PAGE_LINK, "page2", "help"),
                new Run(RunKind.TEXT, " ", null),
                new Run(RunKind.LINK, "site", "https://example.com"));
    }

    @Test
    void leavesUnclosedMarkersAsText() {
        assertThat(ContentMarkdown.inline("2 * 3 = 6, a [ bracket and `tick"))
                .containsExactly(new Run(RunKind.TEXT, "2 * 3 = 6, a [ bracket and `tick", null));
    }

    @Test
    void tellsPagesFromAddresses() {
        assertThat(ContentMarkdown.pageIdOf("#/orders")).isEqualTo("orders");
        assertThat(ContentMarkdown.pageIdOf("page:help-desk")).isEqualTo("help-desk");
        assertThat(ContentMarkdown.pageIdOf("#/Not A Page")).isNull();
        assertThat(ContentMarkdown.isExternal("https://example.com/x")).isTrue();
        assertThat(ContentMarkdown.isExternal("mailto:a@b.c")).isTrue();
        assertThat(ContentMarkdown.isExternal("javascript:alert(1)")).isFalse();
        assertThat(ContentMarkdown.isExternal("https://")).isFalse();
    }

    @Test
    void rendersTextAsQuotedStringsOnly() {
        String jsx = EntityScaffoldContext.contentJsx(ContentMarkdown.parse("Say <b>hi</b> & 'bye' {x}"));
        assertThat(jsx).isEqualTo("        <p className=\"text-sm leading-relaxed text-fg\">{'Say <b>hi</b> & \\'bye\\' {x}'}</p>\n");
    }
}
