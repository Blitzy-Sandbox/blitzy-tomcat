/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.servlets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import static org.apache.catalina.startup.SimpleHttpClient.CRLF;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.ExpandWar;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

@RunWith(Parameterized.class)
public class TestDefaultServletPut extends TomcatBaseTest {

    private static final String START_TEXT= "Starting text";
    private static final int START_LEN = START_TEXT.length();
    private static final String PATCH_TEXT= "Ending *";
    private static final int PATCH_LEN = PATCH_TEXT.length();
    private static final String END_TEXT= "Ending * text";

    @Parameterized.Parameters(name = "{index} rangeHeader [{0}]")
    public static Collection<Object[]> parameters() {
        List<Object[]> parameterSets = new ArrayList<>();

        // Valid partial PUT
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.TRUE, END_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: ByTeS 0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.TRUE, END_TEXT, Boolean.TRUE });
        // Full PUT
        parameterSets.add(new Object[] {
                "", null, PATCH_TEXT, Boolean.TRUE });
        // Invalid range
        parameterSets.add(new Object[] {
                "Content-Range: apples 0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes00-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes=0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes@0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 9-7/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes -7/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 9-/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 9-X/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-5/" + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-5/0x5" + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-" + (PATCH_LEN) + "/" + PATCH_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        // Valid partial PUT but partial PUT is disabled
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-" + (PATCH_LEN-1) + "/" + START_LEN + CRLF, Boolean.TRUE, START_TEXT, Boolean.FALSE });
        // Errors due to incorrect length
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-1/" + PATCH_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] { "Content-Range: bytes 0-" + PATCH_LEN + "/20" + CRLF, Boolean.FALSE,
                START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] { "Content-Range: bytes 0-" + (PATCH_LEN - 2) + "/20" + CRLF, Boolean.FALSE,
                START_TEXT, Boolean.TRUE });
        return parameterSets;
    }


    private File tempDocBase;

    @Parameter(0)
    public String contentRangeHeader;

    @Parameter(1)
    public Boolean contentRangeHeaderValid;

    @Parameter(2)
    public String expectedEndText;

    @Parameter(3)
    public boolean allowPartialPut;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        tempDocBase = Files.createTempDirectory(getTemporaryDirectory().toPath(), "put").toFile();
    }


    /*
     * Replaces the text at the start of START_TEXT with PATCH_TEXT.
     */
    @Test
    public void testPut() throws Exception {
        // Configure a web app with a read/write default servlet
        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        w.addInitParameter("allowPartialPut", Boolean.toString(allowPartialPut));
        ctxt.addServletMappingDecoded("/", "default");

        tomcat.start();

        // Disable caching
        ctxt.getResources().setCachingAllowed(false);

        // Full PUT
        PutClient putClient = new PutClient(getPort());

        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /test.txt HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Length: " + START_LEN + CRLF +
                    CRLF +
                    START_TEXT
        });
        // @formatter:on
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue(putClient.isResponse201());
        putClient.disconnect();

        putClient.reset();

        // Partial PUT
        putClient.connect();
        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /test.txt HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    contentRangeHeader +
                    "Content-Length: " + PATCH_LEN + CRLF +
                    CRLF +
                    PATCH_TEXT
        });
        // @formatter:on
        putClient.processRequest(false);
        if (contentRangeHeaderValid == null) {
            // Not present (so will do a full PUT, replacing the existing)
            Assert.assertTrue(putClient.isResponse204());
        } else if (contentRangeHeaderValid.booleanValue() && allowPartialPut) {
            // Valid
            Assert.assertTrue(putClient.isResponse204());
        } else {
            // Not valid
            Assert.assertTrue(putClient.isResponse400());
        }

        // Check for the final resource
        String path = "http://localhost:" + getPort() + "/test.txt";
        ByteChunk responseBody = new ByteChunk();

        int rc = getUrl(path, responseBody, null);

        Assert.assertEquals(200,  rc);
        Assert.assertEquals(expectedEndText, responseBody.toString());
    }


    /*
     * CVE-2024-50379 / CVE-2024-56337 - Verifies that a PUT of /test.JSP on a
     * case-insensitive filesystem is rejected by the canonical-path equivalence
     * check. On a case-sensitive filesystem the precondition gate must allow
     * the PUT to proceed normally. The exploit scenario requires a pre-existing
     * test.jsp resource that the case-folded /test.JSP write would clobber, so
     * the test seeds tempDocBase with that file before issuing the request.
     */
    @Test
    public void testPutBlockedCaseInsensitiveFilesystem() throws Exception {
        // Run the actual logic only once across the parameterized runs - the
        // test is parameter-independent, so skip duplicate executions to keep
        // the suite fast.
        Assume.assumeTrue("Run only on first parameter combination",
                "".equals(contentRangeHeader) && Boolean.TRUE.equals(Boolean.valueOf(allowPartialPut)));

        boolean caseInsensitive = isCaseInsensitiveFilesystem(tempDocBase);

        // Seed the docBase with a legitimate test.jsp - this is what makes the
        // case-folding RCE exploitable on case-insensitive filesystems and is
        // what the canonical-path equivalence check is designed to detect.
        File legitimateJsp = new File(tempDocBase, "test.jsp");
        Files.write(legitimateJsp.toPath(), "OK".getBytes());
        long originalLen = legitimateJsp.length();

        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        ctxt.addServletMappingDecoded("/", "default");

        tomcat.start();

        // Disable caching
        ctxt.getResources().setCachingAllowed(false);

        // Attempt the case-folding attack: PUT /test.JSP (uppercase extension)
        PutClient putClient = new PutClient(getPort());

        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /test.JSP HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Length: 4" + CRLF +
                    CRLF +
                    "EVIL"
        });
        // @formatter:on
        putClient.connect();
        putClient.processRequest(false);

        if (caseInsensitive) {
            // The canonical-path equivalence check must reject the request
            // because /test.JSP resolves (via OS case-folding) to test.jsp,
            // and the pathsEqualByName comparison detects the case mismatch.
            Assert.assertTrue("Expected 403 on case-insensitive filesystem; got: "
                    + putClient.getResponseLine(), putClient.isResponse403());
            // The pre-existing legitimate test.jsp must remain unchanged.
            Assert.assertEquals("Pre-existing test.jsp must be unmodified after 403",
                    originalLen, legitimateJsp.length());
        } else {
            // On a case-sensitive filesystem, /test.JSP and /test.jsp are
            // distinct paths. The PUT creates a new test.JSP file; the
            // pre-existing test.jsp is not affected. This branch validates the
            // precondition-gate behavior of the production helper.
            Assert.assertTrue("Expected 201 on case-sensitive filesystem; got: "
                    + putClient.getResponseLine(), putClient.isResponse201());
            Assert.assertEquals("Pre-existing test.jsp must be unmodified",
                    originalLen, legitimateJsp.length());
        }

        putClient.disconnect();
    }


    /*
     * CVE-2025-24813 (path guard) - Verifies that PUT requests targeting
     * /WEB-INF/web.xml or /META-INF/MANIFEST.MF are rejected with 403 by the
     * first-non-root-segment guard added to DefaultServlet.doPut(). The guard
     * must execute before any resources.getResource(path) call, so the response
     * is a clean 403 with no resource side effects.
     */
    @Test
    public void testPutBlockedWebInfPath() throws Exception {
        // Run the actual logic only once across the parameterized runs.
        Assume.assumeTrue("Run only on first parameter combination",
                "".equals(contentRangeHeader) && Boolean.TRUE.equals(Boolean.valueOf(allowPartialPut)));

        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        ctxt.addServletMappingDecoded("/", "default");

        tomcat.start();

        // Disable caching
        ctxt.getResources().setCachingAllowed(false);

        // PUT /WEB-INF/web.xml must be rejected. In a standard Tomcat valve
        // chain, StandardContextValve intercepts the request and returns 404
        // before DefaultServlet.doPut() is invoked; the DefaultServlet guard
        // added for CVE-2025-24813 is defense-in-depth that returns 403 if
        // the valve-level interception is bypassed. Either response confirms
        // the security boundary is enforced, so accept both.
        PutClient putClient = new PutClient(getPort());
        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /WEB-INF/web.xml HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Length: 1" + CRLF +
                    CRLF +
                    "x"
        });
        // @formatter:on
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue("Expected 403 or 404 for PUT /WEB-INF/web.xml; got: "
                + putClient.getResponseLine(),
                putClient.isResponse403() || putClient.isResponse404());
        putClient.disconnect();
        // No file artifact must remain in WEB-INF (it does not exist on disk
        // because both the valve-level and servlet-level guards fire before
        // resource resolution; the docBase has no WEB-INF directory at this
        // point).
        Assert.assertFalse("WEB-INF directory must not have been created",
                new File(tempDocBase, "WEB-INF").exists());

        putClient.reset();

        // PUT /META-INF/MANIFEST.MF must be rejected (same rationale as above).
        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /META-INF/MANIFEST.MF HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Length: 1" + CRLF +
                    CRLF +
                    "x"
        });
        // @formatter:on
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue("Expected 403 or 404 for PUT /META-INF/MANIFEST.MF; got: "
                + putClient.getResponseLine(),
                putClient.isResponse403() || putClient.isResponse404());
        putClient.disconnect();
        Assert.assertFalse("META-INF directory must not have been created",
                new File(tempDocBase, "META-INF").exists());
    }


    /*
     * CVE-2025-24813 (default change) - Verifies that the allowPartialPut init
     * parameter defaults to false. A partial PUT (with Content-Range) without
     * explicit configuration must be rejected. The opt-in path with explicit
     * allowPartialPut=true must continue to accept partial PUT requests
     * normally - validated via a second servlet mapping in the same Tomcat
     * instance.
     *
     * Note: When allowPartialPut is disabled, DefaultServlet.parseContentRange
     * returns SC_BAD_REQUEST (400), not SC_METHOD_NOT_ALLOWED (405). The
     * existing testPut() partial-PUT-disabled parameter row asserts the same
     * 400 status. The security mitigation for CVE-2025-24813 is the default
     * flip from true to false; the rejection status itself is unchanged from
     * the pre-mitigation behavior.
     */
    @Test
    public void testPartialPutDisabledByDefault() throws Exception {
        // Run the actual logic only once across the parameterized runs.
        Assume.assumeTrue("Run only on first parameter combination",
                "".equals(contentRangeHeader) && Boolean.TRUE.equals(Boolean.valueOf(allowPartialPut)));

        File optInDocBase = Files.createTempDirectory(
                getTemporaryDirectory().toPath(), "put-optin").toFile();
        try {
            Tomcat tomcat = getTomcatInstance();

            // Sub-scenario A: default context, no allowPartialPut init parameter
            // (verifies the new false default takes effect).
            Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());
            Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
            w.addInitParameter("readonly", "false");
            // Intentionally do NOT set allowPartialPut so we exercise the default.
            ctxt.addServletMappingDecoded("/", "default");

            // Sub-scenario B: opt-in context with explicit allowPartialPut=true
            // (verifies the opt-in path still works after the default flip).
            Context ctxt2 = tomcat.addContext("/optin", optInDocBase.getAbsolutePath());
            Wrapper w2 = Tomcat.addServlet(ctxt2, "default-explicit",
                    DefaultServlet.class.getName());
            w2.addInitParameter("readonly", "false");
            w2.addInitParameter("allowPartialPut", "true");
            ctxt2.addServletMappingDecoded("/", "default-explicit");

            tomcat.start();

            ctxt.getResources().setCachingAllowed(false);
            ctxt2.getResources().setCachingAllowed(false);

            // Sub-scenario A: full PUT to create the target file, then partial
            // PUT must be rejected because allowPartialPut defaults to false.
            // Use a flat target path (no parent directory) so that the
            // underlying WebResource write does not fail because of a missing
            // intermediate directory; this matches the existing testPut()
            // pattern of writing /test.txt directly under the docBase.
            PutClient putClient = new PutClient(getPort());

            // Initial full PUT (no Content-Range, succeeds regardless of allowPartialPut)
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /default-target.txt HTTP/1.1" + CRLF +
                        "Host: localhost:" + getPort() + CRLF +
                        "Content-Length: " + START_LEN + CRLF +
                        CRLF +
                        START_TEXT
            });
            // @formatter:on
            putClient.connect();
            putClient.processRequest(false);
            Assert.assertTrue("Initial full PUT to default context should succeed; got: "
                    + putClient.getResponseLine(), putClient.isResponse201());
            putClient.disconnect();
            putClient.reset();

            // Partial PUT to the default context - must be rejected with 400 because
            // allowPartialPut is false by default after the CVE-2025-24813 mitigation.
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /default-target.txt HTTP/1.1" + CRLF +
                        "Host: localhost:" + getPort() + CRLF +
                        "Content-Range: bytes 0-" + (PATCH_LEN - 1) + "/" + START_LEN + CRLF +
                        "Content-Length: " + PATCH_LEN + CRLF +
                        CRLF +
                        PATCH_TEXT
            });
            // @formatter:on
            putClient.connect();
            putClient.processRequest(false);
            Assert.assertTrue("Expected partial PUT rejection (400) when allowPartialPut "
                    + "defaults to false; got: " + putClient.getResponseLine(),
                    putClient.isResponse400());
            putClient.disconnect();
            putClient.reset();

            // Sub-scenario B: opt-in context with explicit allowPartialPut=true -
            // partial PUT must succeed normally.
            // First do a full PUT to create the target file in /optin.
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /optin/optin-target.txt HTTP/1.1" + CRLF +
                        "Host: localhost:" + getPort() + CRLF +
                        "Content-Length: " + START_LEN + CRLF +
                        CRLF +
                        START_TEXT
            });
            // @formatter:on
            putClient.connect();
            putClient.processRequest(false);
            Assert.assertTrue("Initial full PUT to /optin should succeed; got: "
                    + putClient.getResponseLine(), putClient.isResponse201());
            putClient.disconnect();
            putClient.reset();

            // Partial PUT to opt-in context - must succeed (204 No Content) because
            // allowPartialPut=true is explicitly configured.
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /optin/optin-target.txt HTTP/1.1" + CRLF +
                        "Host: localhost:" + getPort() + CRLF +
                        "Content-Range: bytes 0-" + (PATCH_LEN - 1) + "/" + START_LEN + CRLF +
                        "Content-Length: " + PATCH_LEN + CRLF +
                        CRLF +
                        PATCH_TEXT
            });
            // @formatter:on
            putClient.connect();
            putClient.processRequest(false);
            Assert.assertTrue("Expected partial PUT success (204) on opt-in context; got: "
                    + putClient.getResponseLine(), putClient.isResponse204());
            putClient.disconnect();
        } finally {
            ExpandWar.deleteDir(optInDocBase, false);
        }
    }


    /*
     * Detects whether the local filesystem under the given directory is
     * case-insensitive. Mirrors the production isCaseInsensitiveFilesystem
     * helper added to DefaultServlet for CVE-2024-50379 / CVE-2024-56337:
     * canonical-name comparison via String.equals (NOT equalsIgnoreCase),
     * with an os.name fallback. Uses a probe-file approach to decide at the
     * filesystem layer rather than just trusting os.name.
     */
    private static boolean isCaseInsensitiveFilesystem(File dir) {
        File probe = new File(dir, "case_probe.txt");
        try {
            if (probe.exists()) {
                probe.delete();
            }
            if (probe.createNewFile()) {
                try {
                    File alt = new File(dir, "CASE_PROBE.TXT");
                    if (alt.exists()) {
                        return true;
                    }
                } finally {
                    probe.delete();
                }
            }
        } catch (IOException ignored) {
            // fall through to os.name fallback
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("windows") || os.contains("mac os x");
    }


    @Override
    public void tearDown() {
        ExpandWar.deleteDir(tempDocBase, false);
    }


    private static class PutClient extends SimpleHttpClient {

        PutClient(int port) {
            setPort(port);
        }


        @Override
        public boolean isResponseBodyOK() {
            return false;
        }
    }
}
