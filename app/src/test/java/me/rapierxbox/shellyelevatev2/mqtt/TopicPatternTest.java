package me.rapierxbox.shellyelevatev2.mqtt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import me.rapierxbox.shellyelevatev2.Constants;

public class TopicPatternTest {

    @Test
    public void idSegmentBecomesPlaceholder() {
        assertEquals(Constants.MQTT_TOPIC_SLEEP_BUTTON,
                ShellyElevateMQTTCallback.topicPattern("shellyelevatev2/kitchen/sleep", "kitchen"));
    }

    @Test
    public void idThatAppearsInFixedPartsStillMatches() {
        assertEquals(Constants.MQTT_TOPIC_RELAY_COMMAND,
                ShellyElevateMQTTCallback.topicPattern("shellyelevatev2/relay/relay_command", "relay"));
        assertEquals(Constants.MQTT_TOPIC_UPDATE,
                ShellyElevateMQTTCallback.topicPattern("shellyelevatev2/shelly/update", "shelly"));
    }

    @Test
    public void foreignTopicsStayUntouched() {
        assertEquals(Constants.MQTT_TOPIC_HOME_ASSISTANT_STATUS,
                ShellyElevateMQTTCallback.topicPattern("homeassistant/status", "kitchen"));
        assertEquals("shellyelevatev2/other/sleep",
                ShellyElevateMQTTCallback.topicPattern("shellyelevatev2/other/sleep", "kitchen"));
    }
}
