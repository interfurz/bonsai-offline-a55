package de.bonsai.offline;

import android.content.Context;
import android.net.Uri;
import android.widget.TextView;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.LinkResolverDef;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.SoftBreakAddsNewLinePlugin;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/** Native, offline Markdown presentation; source text stays in the chat model. */
final class AnswerMarkdown {
    private final Markwon markwon;

    AnswerMarkdown(Context context, DesignTokens tokens) {
        LinkResolverDef links=new LinkResolverDef();
        markwon=Markwon.builder(context)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(new AbstractMarkwonPlugin() {
                @Override public void configureTheme(MarkwonTheme.Builder builder) {
                    builder.linkColor(tokens.primary)
                        .codeTextColor(tokens.text).codeBackgroundColor(tokens.surface)
                        .blockQuoteColor(tokens.border);
                }
                @Override public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                    builder.linkResolver((view,link)->{
                        if(isAllowedLink(link))links.resolve(view,link);
                    });
                }
            }).build();
        // No image/HTML plugins: model output cannot load remote images or run HTML.
    }

    boolean setText(TextView view, String source, boolean formatted) {
        Object previous=view.getTag(R.id.answer_text_source);
        if(previous instanceof Source) {
            Source saved=(Source)previous;
            if(saved.formatted==formatted && saved.text.equals(source))return false;
        }
        if(formatted)markwon.setMarkdown(view,source);
        else view.setText(source);
        // Compare raw source on later progress ticks, not the rendered text/spans.
        view.setTag(R.id.answer_text_source,new Source(source,formatted));
        return true;
    }

    static boolean isAllowedLink(String link) {
        String scheme=Uri.parse(link).getScheme();
        return "https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)
            ||"mailto".equalsIgnoreCase(scheme);
    }

    private static final class Source {
        final String text;
        final boolean formatted;
        Source(String text,boolean formatted){this.text=text;this.formatted=formatted;}
    }
}
