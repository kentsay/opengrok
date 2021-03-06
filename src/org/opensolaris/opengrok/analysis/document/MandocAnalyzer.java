/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2005, 2017, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2017, Chris Fraire <cfraire@me.com>.
 */
package org.opensolaris.opengrok.analysis.document;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.opensolaris.opengrok.analysis.FileAnalyzer;
import org.opensolaris.opengrok.analysis.FileAnalyzerFactory;
import org.opensolaris.opengrok.analysis.JFlexTokenizer;
import org.opensolaris.opengrok.analysis.StreamSource;
import org.opensolaris.opengrok.analysis.TextAnalyzer;
import org.opensolaris.opengrok.analysis.WriteXrefArgs;
import org.opensolaris.opengrok.analysis.Xrefer;
import org.opensolaris.opengrok.search.QueryBuilder;

/**
 * Analyzes mandoc files
 */
public class MandocAnalyzer extends TextAnalyzer {

    /**
     * Creates a new instance of MandocAnalyzer
     * @param factory defined instance for the analyzer
     */
    protected MandocAnalyzer(FileAnalyzerFactory factory) {
        super(factory, new JFlexTokenizer(new TroffFullTokenizer(
            FileAnalyzer.dummyReader)));
    }

    @Override
    public void analyze(Document doc, StreamSource src, Writer xrefOut)
        throws IOException {

        // this is to explicitly use appropriate analyzers tokenstream to
        // workaround #1376 symbols search works like full text search
        this.symbolTokenizer.setReader(getReader(src.getStream()));
        TextField full = new TextField(QueryBuilder.FULL, symbolTokenizer);
        doc.add(full);

        if (xrefOut != null) {
            try (Reader in = getReader(src.getStream())) {
                WriteXrefArgs args = new WriteXrefArgs(in, xrefOut);
                args.setProject(project);
                writeXref(args);
            }
        }
    }

    /**
     * Creates a wrapped {@link MandocXref} instance.
     * @param reader the data to produce xref for
     * @return an xref instance
     */
    @Override
    protected Xrefer newXref(Reader reader) {
        return new MandocXref(reader);
    }
}
