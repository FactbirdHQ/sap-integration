import com.sap.gateway.ip.core.customdev.util.Message;
import java.util.HashMap;
import groovy.json.*;

def Message processData(Message message) {
    def body = message.getBody(String.class);    
    def jsonSlurper = new JsonSlurper();
    def payload = jsonSlurper.parseText(body);
    
    message.setProperty("manufacturingOrder", payload.data.ManufacturingOrder);
    return message;
}