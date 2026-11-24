package org.dynmap.servlet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.*;
import org.dynmap.*;
import org.json.simple.parser.JSONParser;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
class ConfigJSSecurityTest {
    @Test void configuredUrlsRemainDataIncludingQuotesLineBreaksAndScriptMarkup() throws Exception {
        var core=new DynmapCore();core.configuration=new ConfigurationNode();
        String attack="tiles/';alert(1);//\n</script>\u2028";core.configuration.put("url",java.util.Map.of("tiles",attack));
        var bytes=new ByteArrayOutputStream();var response=mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(new ServletOutputStream(){public void write(int b){bytes.write(b);}public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}});
        new ConfigJSServlet(core).doGet(mock(HttpServletRequest.class),response);
        String script=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(script.startsWith("var config = "));assertTrue(script.endsWith(";\n"));
        var parsed=(JSONObject)new JSONParser().parse(script.substring(13,script.length()-2));
        assertEquals(attack,((JSONObject)parsed.get("url")).get("tiles"));assertFalse(script.contains("</script>"));
    }
}
