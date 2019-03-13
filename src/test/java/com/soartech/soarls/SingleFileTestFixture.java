package com.soartech.soarls;

/**
 * Create a Soar file at
 * tests/resources/<relativeWorkspaceRoot>/<file>, then extend this to
 * create a test class.
 *
 * See LanguageServerTestFixture for more details.
 *
 * This is largely borrowed from the Kotlin language server.
 */
class SingleFileTestFixture extends LanguageServerTestFixture {
    final String file;

    SingleFileTestFixture(String relativeWorkspaceRoot, String file) throws Exception {
        super(relativeWorkspaceRoot);
        this.file = file;
        open(file);
    }
}
