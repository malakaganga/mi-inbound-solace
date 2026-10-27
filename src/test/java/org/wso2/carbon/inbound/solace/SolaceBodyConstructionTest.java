/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.inbound.solace;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.MapMessage;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLContentMessage;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.OMText;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2SynapseEnvironment;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import javax.activation.DataHandler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The body of the injected context is built by the builder registered for its content type, and the context
 * describes that body: messageType is the builder key, ContentType and the transport header carry the complete
 * type, setCharacterEncoding is false and no declaration encoding is left behind.
 */
public class SolaceBodyConstructionTest {

    private static final JCSMPFactory FACTORY = JCSMPFactory.onlyInstance();

    private static final String PAYLOAD_NS = "http://ws.apache.org/commons/ns/payload";

    private static SynapseEnvironment environment;

    @BeforeClass
    public static void environment() throws Exception {
        environment = TestEnvironments.withPackBuilders();
    }

    private static SolaceUtils.Payload read(BytesXMLMessage message, String contentType) throws Exception {
        return SolaceUtils.readPayload(message, contentType, false, Collections.emptyMap());
    }

    private static BytesMessage bytes(String declared, byte[] data) {
        BytesMessage message = FACTORY.createMessage(BytesMessage.class);
        if (declared != null) {
            message.setHTTPContentType(declared);
        }
        message.setData(data);
        return message;
    }

    private static MessageContext build(SolaceUtils.Payload payload) throws Exception {
        org.apache.synapse.MessageContext context = SolaceUtils.createMessageContext(environment, payload);
        return ((Axis2MessageContext) context).getAxis2MessageContext();
    }

    private static OMElement firstElement(MessageContext context) {
        return context.getEnvelope().getBody().getFirstElement();
    }

    private static byte[] binary(MessageContext context) throws Exception {
        OMElement wrapper = firstElement(context);
        assertEquals(PAYLOAD_NS, wrapper.getNamespace().getNamespaceURI());
        assertEquals("binary", wrapper.getLocalName());
        DataHandler handler = (DataHandler) ((OMText) wrapper.getFirstOMChild()).getDataHandler();
        try (InputStream in = handler.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static void assertLocations(MessageContext context, String messageType, String contentType) {
        assertEquals(messageType, context.getProperty(Constants.Configuration.MESSAGE_TYPE));
        assertEquals(contentType, context.getProperty(Constants.Configuration.CONTENT_TYPE));
        assertEquals("false", context.getProperty(Constants.Configuration.SET_CONTENT_TYPE_CHARACTER_ENCODING));
        Map<?, ?> headers = (Map<?, ?>) context.getProperty(MessageContext.TRANSPORT_HEADERS);
        assertEquals(contentType, headers.get("Content-Type"));
        assertNull(context.getProperty(Constants.Configuration.XML_DECLARATION_ENCODING));
    }

    private static byte[] allBytes() {
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    @Test
    public void mapMessageIsBuiltAsJson() throws Exception {
        MapMessage message = FACTORY.createMessage(MapMessage.class);
        SDTMap body = FACTORY.createMap();
        body.putString("id", "order42");
        message.setMap(body);

        MessageContext context = build(read(message, null));
        assertLocations(context, "application/json", "application/json; charset=UTF-8");
        assertEquals("UTF-8", context.getProperty(Constants.Configuration.CHARACTER_SET_ENCODING));
        assertEquals("{\"id\":\"order42\"}", JsonUtil.jsonPayloadToString(context));
    }

    @Test
    public void declaredLatin1JsonIsBuiltFromCanonicalUtf8() throws Exception {
        byte[] latin1 = "{\"n\":\"café\"}".getBytes(StandardCharsets.ISO_8859_1);
        MessageContext context = build(read(bytes("application/json; charset=ISO-8859-1", latin1),
                "application/json"));
        assertLocations(context, "application/json", "application/json; charset=UTF-8");
        assertEquals("{\"n\":\"café\"}", JsonUtil.jsonPayloadToString(context));
    }

    @Test
    public void textMessageIsBuiltAsText() throws Exception {
        TextMessage message = FACTORY.createMessage(TextMessage.class);
        message.setText("hello café");

        MessageContext context = build(read(message, null));
        assertLocations(context, "text/plain", "text/plain; charset=UTF-8");
        assertEquals("text", firstElement(context).getLocalName());
        assertEquals("hello café", firstElement(context).getText());
    }

    @Test
    public void xmlContentMessageIsBuiltAsXml() throws Exception {
        XMLContentMessage message = FACTORY.createMessage(XMLContentMessage.class);
        message.setXMLContent("<order><id>42</id></order>");

        MessageContext context = build(read(message, null));
        assertLocations(context, "application/xml", "application/xml; charset=UTF-8");
        assertEquals("order", firstElement(context).getLocalName());
        assertEquals("42", firstElement(context).getFirstElement().getText());
    }

    @Test
    public void declarationEncodingIsRemovedAfterTheBuild() throws Exception {
        byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><n>café</n>"
                .getBytes(StandardCharsets.ISO_8859_1);
        SolaceUtils.Payload payload = read(bytes("application/xml; charset=ISO-8859-1", latin1), null);

        // Control: the XML builder records the encoding the document declares when it differs from the
        // charset of the bytes.
        MessageContext control = new MessageContext();
        control.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, "UTF-8");
        new ApplicationXMLBuilder().processDocument(new ByteArrayInputStream(payload.bytes),
                "application/xml; charset=UTF-8", control);
        assertEquals("ISO-8859-1", control.getProperty(Constants.Configuration.XML_DECLARATION_ENCODING));

        MessageContext context = build(payload);
        assertLocations(context, "application/xml", "application/xml; charset=UTF-8");
        assertEquals("café", firstElement(context).getText());
    }

    @Test
    public void undeclaredBytesAreTheNativeBinaryElementForEveryAttempt() throws Exception {
        byte[] data = allBytes();
        SolaceUtils.Payload payload = read(bytes(null, data), null);

        MessageContext first = build(payload);
        MessageContext second = build(payload);
        assertLocations(first, "application/octet-stream", "application/octet-stream");
        assertArrayEquals(data, binary(first));
        assertArrayEquals(data, binary(second));
    }

    @Test
    public void dataSourceBuilderGetsARepeatableSource() throws Exception {
        byte[] data = allBytes();
        MessageContext context = build(read(bytes("application/binary", data), null));
        assertLocations(context, "application/binary", "application/binary");
        assertArrayEquals(data, binary(context));
        assertArrayEquals(data, binary(context));
    }

    @Test
    public void unregisteredTypesTakeTheFamilyFallback() throws Exception {
        MessageContext csv = build(read(bytes("text/csv", "a,b".getBytes(StandardCharsets.UTF_8)), null));
        assertLocations(csv, "text/plain", "text/csv; charset=UTF-8");
        assertEquals("a,b", firstElement(csv).getText());

        MessageContext problem = build(read(bytes("application/problem+json",
                "{\"title\":\"x\"}".getBytes(StandardCharsets.UTF_8)), null));
        assertLocations(problem, "application/json", "application/problem+json; charset=UTF-8");
        assertEquals("{\"title\":\"x\"}", JsonUtil.jsonPayloadToString(problem));

        MessageContext vendorXml = build(read(bytes("application/vnd.test+xml",
                "<t/>".getBytes(StandardCharsets.UTF_8)), null));
        assertLocations(vendorXml, "application/xml", "application/vnd.test+xml; charset=UTF-8");
        assertEquals("t", firstElement(vendorXml).getLocalName());

        byte[] data = allBytes();
        MessageContext custom = build(read(bytes("application/x-custom-binary", data), null));
        assertLocations(custom, "application/octet-stream", "application/x-custom-binary");
        assertArrayEquals(data, binary(custom));
    }

    @Test
    public void registeredBuilderWinsOverTheFamilyFallback() throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("text/plain", new PlainTextBuilder());
        configuration.addMessageBuilder("application/x-custom", new PlainTextBuilder());
        SynapseEnvironment custom = new Axis2SynapseEnvironment(new ConfigurationContext(configuration),
                new SynapseConfiguration());

        org.apache.synapse.MessageContext context = SolaceUtils.createMessageContext(custom,
                read(bytes("application/x-custom", "marker".getBytes(StandardCharsets.UTF_8)), null));
        MessageContext axis2 = ((Axis2MessageContext) context).getAxis2MessageContext();
        assertLocations(axis2, "application/x-custom", "application/x-custom");
        assertEquals("marker", firstElement(axis2).getText());
    }

    @Test
    public void soapTypesAreBuiltAsPlainXml() throws Exception {
        String envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><a/><b/></s:Body></s:Envelope>";
        MessageContext context = build(read(bytes("text/xml", envelope.getBytes(StandardCharsets.UTF_8)), null));
        assertLocations(context, "application/xml", "text/xml; charset=UTF-8");
        assertEquals("Envelope", firstElement(context).getLocalName());

        MessageContext soap12 = build(read(bytes("application/soap+xml; action=\"urn:x\"",
                "<r/>".getBytes(StandardCharsets.UTF_8)), null));
        assertLocations(soap12, "application/xml", "application/soap+xml; action=\"urn:x\"; charset=UTF-8");
    }

    @Test
    public void formsTakeTheOpaqueBinaryPath() throws Exception {
        String declared = "application/x-www-form-urlencoded; charset=ISO-8859-1";
        byte[] data = "a+b=caf%E9".getBytes(StandardCharsets.US_ASCII);
        MessageContext form = build(read(bytes(declared, data), null));
        assertLocations(form, "application/octet-stream", declared);
        assertEquals("ISO-8859-1", form.getProperty(Constants.Configuration.CHARACTER_SET_ENCODING));
        assertArrayEquals(data, binary(form));

        String multipart = "multipart/form-data; boundary=xyz";
        byte[] parts = "--xyz\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--xyz--\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        MessageContext multi = build(read(bytes(multipart, parts), null));
        assertLocations(multi, "application/octet-stream", multipart);
        assertArrayEquals(parts, binary(multi));
    }

    @Test
    public void legacyBase64OptionKeepsTheTextBody() throws Exception {
        SolaceUtils.Payload payload = SolaceUtils.readPayload(bytes("application/json", new byte[] {1, 2, 3}),
                null, true, Collections.emptyMap());
        MessageContext context = build(payload);
        assertEquals("application/octet-stream", context.getProperty(Constants.Configuration.MESSAGE_TYPE));
        assertEquals("application/octet-stream", context.getProperty(Constants.Configuration.CONTENT_TYPE));
        // The 2.0.0 element: message in the payload namespace, holding the base64 text.
        assertEquals("message", firstElement(context).getLocalName());
        assertEquals(PAYLOAD_NS, firstElement(context).getNamespace().getNamespaceURI());
        assertEquals("AQID", firstElement(context).getText());
    }

    @Test
    public void malformedXmlRootFailsTheBuild() throws Exception {
        SolaceUtils.Payload payload = read(bytes("application/xml", "not xml".getBytes(StandardCharsets.UTF_8)),
                null);
        try {
            build(payload);
            fail("a malformed root must fail the build");
        } catch (OMException expected) {
            // The worker logs the build failure and applies the injection rejected row.
        }
    }

    @Test
    public void xmlMalformedAfterItsRootIsBuiltAndFailsWhereItIsRead() throws Exception {
        MessageContext context = build(read(bytes("application/xml",
                "<root><item>value</root>".getBytes(StandardCharsets.UTF_8)), null));
        assertEquals("root", firstElement(context).getLocalName());
        try {
            firstElement(context).toString();
            fail("the rest of the document must fail where it is first read");
        } catch (OMException expected) {
            // The sequence meets the failure and the settlement table decides.
        }
    }

    @Test
    public void malformedJsonFailsTheBuild() throws Exception {
        SolaceUtils.Payload payload = read(bytes("application/json", "not json".getBytes(StandardCharsets.UTF_8)),
                null);
        try {
            build(payload);
            fail("JSON the builder rejects must fail the build");
        } catch (AxisFault expected) {
            // The worker logs the build failure and applies the injection rejected row.
        }
    }

    @Test
    public void jsonTheBuilderAcceptsAtItsStartIsBuiltAndFailsWhereItIsRead() throws Exception {
        MessageContext context = build(read(bytes("application/json",
                "{not valid JSON".getBytes(StandardCharsets.UTF_8)), null));
        assertEquals("jsonObject", firstElement(context).getLocalName());
        try {
            firstElement(context).getFirstElement();
            fail("the rest of the document must fail where it is first read");
        } catch (Exception expected) {
            // The platform JSON builder checks the first character only. The sequence meets the failure.
        }
    }

    @Test
    public void noRegisteredBuilderFailsTheBuild() throws Exception {
        try {
            SolaceUtils.createMessageContext(TestEnvironments.withoutBuilders(),
                    read(bytes(null, new byte[] {1}), null));
            fail("a pack without a builder for the type must fail the build");
        } catch (AxisFault expected) {
            assertTrue(expected.getMessage().contains("application/octet-stream"));
        }
    }

    @Test
    public void everyAttemptHasItsOwnContextAndReader() throws Exception {
        SolaceUtils.Payload payload = read(bytes("text/plain", "same".getBytes(StandardCharsets.UTF_8)), null);
        MessageContext first = build(payload);
        MessageContext second = build(payload);
        assertFalse(first == second);
        assertFalse(first.getEnvelope() == second.getEnvelope());
        assertEquals("same", firstElement(second).getText());
        assertEquals("same", firstElement(first).getText());
    }
}
