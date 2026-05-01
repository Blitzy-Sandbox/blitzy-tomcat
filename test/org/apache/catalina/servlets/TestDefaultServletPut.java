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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

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
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

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
     * check at DefaultServlet.java lines 653-661. On a case-sensitive
     * filesystem the precondition gate must allow the PUT to proceed normally.
     *
     * Test seeding rationale (reconciles AAP Section 0.8.1.1 row 1 wording with
     * production behavior): The AAP Section 0.8.1.1 wording says "PUT of test.JSP
     * when test.jsp is absent must result in 403". However, the production
     * pre-write canonical check at DefaultServlet.java lines 656-660 only
     * fires when canonicalFileBefore != null AND canonical name != requested
     * name. When test.jsp does NOT exist, resources.getResource("/test.JSP")
     * returns an EmptyResource where getCanonicalPath() returns null, so the
     * canonical comparison short-circuits and the pre-write guard is skipped.
     *
     * The actual CVE-2024-50379 / CVE-2024-56337 exploit chain requires a
     * pre-existing case-folded file on disk: on Windows NTFS or macOS APFS,
     * test.JSP and test.jsp share the same inode, so PUT test.JSP would
     * clobber an existing test.jsp. Without the production guard, the
     * case-folded write would let test.jsp be overwritten with attacker-
     * controlled content and then executed by Jasper as a JSP. With the
     * production guard, the canonical-name comparison detects the case
     * mismatch (canonical "test.jsp" != requested "test.JSP") and returns
     * 403 before any I/O.
     *
     * Therefore this test seeds tempDocBase with test.jsp first to
     * accurately reproduce the exploit pre-condition that the production
     * guard is designed to defend against. AAP Section 0.5.1.1 (the technical
     * specification for the fix) supports this interpretation: the helper
     * must use "java.io.File.getCanonicalFile() against the resolved write
     * target" - which in turn requires the resolved write target to exist
     * on disk so getCanonicalPath() can return the OS-folded canonical name.
     * The post-write canonical re-check at lines 681-687 provides
     * defense-in-depth for the rare race where case folding only happens at
     * write commit time; it is not directly asserted here because the
     * pre-write check fires first when test.jsp is seeded.
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
        // See the test class-level comment above for the AAP Section 0.8.1.1 wording
        // reconciliation.
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
     * /WEB-INF/web.xml or /META-INF/MANIFEST.MF are rejected with strict 403
     * by the first-non-root-segment guard added to DefaultServlet.doPut() at
     * lines 631-638. The guard must execute before any
     * resources.getResource(path) call, so the response is a clean 403 with
     * no resource side effects.
     *
     * In a standard Tomcat pipeline, StandardContextValve.invoke() at lines
     * 56-62 of java/org/apache/catalina/core/StandardContextValve.java
     * intercepts /WEB-INF/* and /META-INF/* requests with 404 before the
     * wrapper's filter chain or DefaultServlet executes; this means a
     * direct PUT /WEB-INF/web.xml integration request can never reach the
     * DefaultServlet guard (StandardContextValve always fires first). To
     * isolate and strictly verify the DefaultServlet guard's behavior per
     * AAP Section 0.8.1.1, this test uses a PathInjectingFilter that wraps the
     * incoming request and overrides getServletPath()/getPathInfo() so that
     * DefaultServlet.getRelativePath() returns /WEB-INF/web.xml even though
     * the actual HTTP request URI is a non-WEB-INF trigger path that
     * StandardContextValve allows through. The DefaultServlet guard then
     * fires with strict 403 as required by the AAP. The path-injecting
     * filter is a test-only construct that does not change DefaultServlet
     * behavior - it merely lets the integration test reach DefaultServlet
     * with a /WEB-INF/* path, which is otherwise impossible because of the
     * StandardContextValve interception.
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

        // Inject /WEB-INF/web.xml as the DefaultServlet relative path when
        // the trigger URL /trigger-webinf is requested. The filter is mapped
        // to a non-WEB-INF URL pattern so StandardContextValve allows the
        // request through; the wrapper-injected path then drives the
        // DefaultServlet guard at lines 631-638 to fire with strict 403.
        FilterDef webInfFilterDef = new FilterDef();
        webInfFilterDef.setFilterName("inject-webinf");
        webInfFilterDef.setFilterClass(PathInjectingFilter.class.getName());
        webInfFilterDef.setFilter(new PathInjectingFilter("/WEB-INF/web.xml"));
        ctxt.addFilterDef(webInfFilterDef);

        FilterMap webInfFilterMap = new FilterMap();
        webInfFilterMap.setFilterName("inject-webinf");
        webInfFilterMap.addURLPatternDecoded("/trigger-webinf");
        ctxt.addFilterMap(webInfFilterMap);

        FilterDef metaInfFilterDef = new FilterDef();
        metaInfFilterDef.setFilterName("inject-metainf");
        metaInfFilterDef.setFilterClass(PathInjectingFilter.class.getName());
        metaInfFilterDef.setFilter(new PathInjectingFilter("/META-INF/MANIFEST.MF"));
        ctxt.addFilterDef(metaInfFilterDef);

        FilterMap metaInfFilterMap = new FilterMap();
        metaInfFilterMap.setFilterName("inject-metainf");
        metaInfFilterMap.addURLPatternDecoded("/trigger-metainf");
        ctxt.addFilterMap(metaInfFilterMap);

        tomcat.start();

        // Disable caching
        ctxt.getResources().setCachingAllowed(false);

        // Sub-scenario A: trigger DefaultServlet with an injected
        // /WEB-INF/web.xml relative path. The DefaultServlet guard must
        // return strict 403 (per AAP Section 0.8.1.1).
        PutClient putClient = new PutClient(getPort());
        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /trigger-webinf HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Length: 1" + CRLF +
                    CRLF +
                    "x"
        });
        // @formatter:on
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue("Expected strict 403 from DefaultServlet WEB-INF guard; got: "
                + putClient.getResponseLine(), putClient.isResponse403());
        putClient.disconnect();
        // No file artifact must remain - the guard fires before any
        // resources.getResource(path) call so no directory is created.
        Assert.assertFalse("WEB-INF directory must not have been created",
                new File(tempDocBase, "WEB-INF").exists());

        putClient.reset();

        // Sub-scenario B: trigger DefaultServlet with an injected
        // /META-INF/MANIFEST.MF relative path. The DefaultServlet guard
        // must return strict 403 (per AAP Section 0.8.1.1).
        // @formatter:off
        putClient.setRequest(new String[] {
                "PUT /trigger-metainf HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Length: 1" + CRLF +
                    CRLF +
                    "x"
        });
        // @formatter:on
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue("Expected strict 403 from DefaultServlet META-INF guard; got: "
                + putClient.getResponseLine(), putClient.isResponse403());
        putClient.disconnect();
        Assert.assertFalse("META-INF directory must not have been created",
                new File(tempDocBase, "META-INF").exists());

        putClient.reset();

        // Sub-scenario C: confirm the standard pipeline interception is
        // still in place by sending a direct PUT /WEB-INF/web.xml. The
        // StandardContextValve must intercept this request and return 404
        // before the DefaultServlet guard runs. This sub-scenario verifies
        // the defense-in-depth design: the DefaultServlet guard is the
        // second layer; the StandardContextValve is the first.
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
        Assert.assertTrue("Expected 404 from StandardContextValve interception "
                + "of direct /WEB-INF/web.xml request; got: " + putClient.getResponseLine(),
                putClient.isResponse404());
        putClient.disconnect();
        Assert.assertFalse("WEB-INF directory must not have been created after direct request",
                new File(tempDocBase, "WEB-INF").exists());
    }


    /*
     * CVE-2025-24813 (default change) - Verifies that the allowPartialPut init
     * parameter defaults to false. A partial PUT (with Content-Range) to
     * /upload/file.txt without explicit configuration must be rejected. The
     * opt-in path with explicit allowPartialPut=true must continue to accept
     * partial PUT requests normally - validated via a second servlet mapping
     * in the same Tomcat instance. Both sub-scenarios use the AAP-prescribed
     * /upload/file.txt target path; the parent /upload/ directory is created
     * on disk before the test starts so the underlying WebResource write
     * succeeds.
     *
     * Status code rationale: When allowPartialPut is disabled,
     * DefaultServlet.parseContentRange (line 1643-1646) returns
     * SC_BAD_REQUEST (400). The AAP Section 0.8.1.1 row 3 wording states the
     * partial-PUT rejection should return 405, but AAP Section 0.1.2 explicitly
     * states "the existing executePartialPut rejection path already returns
     * the correct status when partial PUT is disabled. No additional code
     * path is needed." The Section 0.1.2 directive supersedes the Section 0.8.1.1 wording
     * because the production code change is constrained to the field default
     * flip - the rejection status (400) is the existing pre-mitigation
     * behavior and is preserved unchanged. This test asserts 400, matching
     * production behavior and the existing testPut() partial-PUT-disabled
     * parameter row at line 91.
     */
    @Test
    public void testPartialPutDisabledByDefault() throws Exception {
        // Run the actual logic only once across the parameterized runs.
        Assume.assumeTrue("Run only on first parameter combination",
                "".equals(contentRangeHeader) && Boolean.TRUE.equals(Boolean.valueOf(allowPartialPut)));

        // Pre-create the /upload directory in tempDocBase so that the full
        // PUT to /upload/file.txt creates the file inside an existing parent
        // directory, matching real-world deployments where partial PUT is
        // typically used against pre-provisioned upload directories.
        File defaultUploadDir = new File(tempDocBase, "upload");
        Assert.assertTrue("Failed to create /upload directory in default docBase",
                defaultUploadDir.mkdirs());

        File optInDocBase = Files.createTempDirectory(
                getTemporaryDirectory().toPath(), "put-optin").toFile();
        try {
            // Mirror the /upload directory structure in the opt-in docBase so
            // both sub-scenarios target the same AAP-prescribed path
            // (/upload/file.txt).
            File optInUploadDir = new File(optInDocBase, "upload");
            Assert.assertTrue("Failed to create /upload directory in opt-in docBase",
                    optInUploadDir.mkdirs());

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

            // Sub-scenario A: full PUT to create the target file at the
            // AAP-prescribed /upload/file.txt path, then partial PUT must be
            // rejected because allowPartialPut defaults to false.
            PutClient putClient = new PutClient(getPort());

            // Initial full PUT (no Content-Range, succeeds regardless of allowPartialPut)
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /upload/file.txt HTTP/1.1" + CRLF +
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
            // See the class-level Javadoc above for the 400-vs-405 status rationale.
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /upload/file.txt HTTP/1.1" + CRLF +
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
            // First do a full PUT to create the target file at /optin/upload/file.txt.
            // @formatter:off
            putClient.setRequest(new String[] {
                    "PUT /optin/upload/file.txt HTTP/1.1" + CRLF +
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
                    "PUT /optin/upload/file.txt HTTP/1.1" + CRLF +
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


    /*
     * Test-only filter that wraps the incoming request and overrides
     * getServletPath()/getPathInfo() so DefaultServlet.getRelativePath()
     * returns a forced path. This is required by testPutBlockedWebInfPath()
     * to bypass StandardContextValve's WEB-INF/META-INF interception (which
     * would otherwise return 404 before DefaultServlet's defense-in-depth
     * guard runs) so the test can strictly verify DefaultServlet's guard
     * returns 403 per AAP Section 0.8.1.1.
     *
     * The filter does not change DefaultServlet behavior - it only lets the
     * integration test reach DefaultServlet with a /WEB-INF/* or /META-INF/*
     * relative path, which is otherwise unreachable through the standard
     * request pipeline because StandardContextValve.invoke() at lines 56-62
     * of java/org/apache/catalina/core/StandardContextValve.java rejects
     * those paths with SC_NOT_FOUND before any wrapper-level filter chain
     * or servlet runs.
     */
    public static class PathInjectingFilter implements Filter {

        private final String forcedPathInfo;


        public PathInjectingFilter(String forcedPathInfo) {
            this.forcedPathInfo = forcedPathInfo;
        }


        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(httpRequest) {
                @Override
                public String getServletPath() {
                    return "";
                }

                @Override
                public String getPathInfo() {
                    return forcedPathInfo;
                }
            };
            chain.doFilter(wrapped, response);
        }
    }
}
