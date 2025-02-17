package me.rapierxbox.shellyelevatev2;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {
    public HttpServer() {
        super(8080);
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        try {
            switch (uri) {
                case "/relay":
                    if (method.equals(Method.GET)) {
                        jsonResponse.put("state", DeviceHelper.getRelay());
                        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                    } else if (method.equals(Method.POST)) {
                        Map<String, String> files = new HashMap<>();
                        session.parseBody(files);
                        String postData = files.get("postData");
                        DeviceHelper.setRelay(Boolean.parseBoolean(postData));
                        jsonResponse.put("state", DeviceHelper.getRelay());
                        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                    } else {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing 'enable' header.\"}");
                    }
                case "/getTemp":
                    jsonResponse.put("temperature", DeviceHelper.getTemperature());
                    return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                case "/getHumidity":
                    jsonResponse.put("humidity", DeviceHelper.getHumidity());
                    return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                default:
                    jsonResponse.put("message", "ShellyElevate by RapierXbox");
                    return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
            }
        } catch (JSONException | IOException | ResponseException e) {
            return newFixedLengthResponse("Internal Server Error!" + e);
        }
    }
}
