/**
 *  Xiaomi Button
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on a4refillpad's version based on original DH by Eric Maycock 2015 and Rave from Lazcad
 *  change log:
 *  Exposes the number of buttons it has. 1!
 *  Cleaner activity log, only reports key events - press, hold and battery level changes
 *  icon for the handler to show in Things list
 *  added 100% battery max
 *  fixed battery parsing problem
 *  added lastcheckin attribute and tile
 *  added a means to also push button in as tile on smartthings app
 *  fixed ios tile label problem and battery bug
 *
 */
metadata {
  definition (name: "Xiaomi Button", namespace: "tommysqueak", author: "Tom Philip") {
    capability "Actuator"
    capability "Sensor"
    capability "Battery"
    capability "Button"
    capability "Holdable Button"
    capability "Switch"
    capability "Momentary"
    capability "Configuration"
    capability "Refresh"
    //   Health Check https://github.com/constjs/jcdevhandlers/commit/ea275dcf5b6ddfb617104e1f8950dd9f7916e276#diff-898033a1cdc1ae113328ecaeab60a1d6

    attribute "lastPress", "string"
    //  TODO: capability "Battery" has the battery level
    attribute "batterylevel", "string"
    attribute "lastCheckin", "string"

    // https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/e5739fd425066e110b94f6e1e88c2347821a6326/devicetypes/smartthings/zigbee-button.src/zigbee-button.groovy

    fingerprint profileId: "0104", deviceId: "0104", inClusters: "0000, 0003", outClusters: "0000, 0004, 0003, 0006, 0008, 0005", manufacturer: "LUMI", model: "lumi.sensor_switch", deviceJoinName: "Xiaomi Button"
  }

  simulator {
    status "button 1 pressed": "on/off: 0"
    status "button 1 released": "on/off: 1"
  }

  preferences{
    input ("holdTime", "number", title: "Minimum time in seconds for a press to count as \"held\"", defaultValue: 4, displayDuringSetup: false)
  }

  tiles(scale: 2) {
    multiAttributeTile(name:"switch", type: "generic", width: 6, height: 4, canChangeIcon: true) {
      tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
        attributeState("on", label: 'push', icon: 'st.Home.home30', action: "momentary.push", backgroundColor:"#53a7c0")
        attributeState("off", label: 'push', icon: 'st.Home.home30', action: "momentary.push", backgroundColor:"#ffffff", nextState: "on")
      }

      tileAttribute("device.lastCheckin", key: "SECONDARY_CONTROL") {
        attributeState("default", label:'Last Update: ${currentValue}',icon: "st.Health & Wellness.health9")
      }
    }

    valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
      state "battery", label:'${currentValue}% battery', unit:""
    }

    standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
      state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
    }

    standardTile("configure", "device.configure", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
      state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
    }

    main (["switch"])
    details(["switch", "battery", "refresh", "configure"])
  }
}

def parse(String description) {
  log.debug "Parsing '${description}'"

  def results = []
  if (description?.startsWith('on/off: '))
    results = parseButtonActionMessage(description)
  if (description?.startsWith('catchall:'))
    results = parseCatchAllMessage(description)

  def now = new Date().format("yyyy MMM dd EEE h:mm:ss a", location.timeZone)
  results << createEvent(name: "lastCheckin", value: now, displayed: false)

  return results;
}

def configure(){
  // http://www.silabs.com/documents/public/miscellaneous/AF-V2-API.pdf
  [
    "zdo bind 0x${device.deviceNetworkId} 1 2 0 {${device.zigbeeId}} {}", "delay 5000",
    "zcl global send-me-a-report 2 0 0x10 1 0 {01}", "delay 500",
    "send 0x${device.deviceNetworkId} 1 2"
  ]
}

def refresh(){
  "st rattr 0x${device.deviceNetworkId} 1 2 0"
  "st rattr 0x${device.deviceNetworkId} 1 0 0"
  log.debug "refreshing"

  // TODO: doesn't do anything, remove it? and the refresh tile?
  createEvent(name: "batterylevel", value: '100', data:[buttonNumber: 1], displayed: false)
}

private ArrayList parseCatchAllMessage(String description) {
  def cluster = zigbee.parse(description)
  log.debug cluster

  if(cluster && cluster.clusterId == 0x0000) {
    return [createBatteryEvent(cluster.data.last())]
  }
  else {
    return []
  }
}

private createBatteryEvent(rawValue) {
  log.debug "Battery '${rawValue}'"
  int batteryLevel = rawValue
  int maxBatteryLevel = 100

  if (batteryLevel > maxBatteryLevel) {
    batteryLevel = maxBatteryLevel
  }

  return createEvent(name: 'battery', value: batteryLevel, unit: "%")
}

private ArrayList parseButtonActionMessage(String message) {
  if (message == 'on/off: 0')     //button pressed
    return createPressEvent()
  else if (message == 'on/off: 1')   //button released
    return createButtonEvent()
}

//this method determines if a press should count as a push or a hold and returns the relevant event type
private ArrayList createButtonEvent() {
  def currentTime = now()
  def startOfPress = device.latestState("lastPress").date.getTime()
  def timeDif = currentTime - startOfPress
  def holdTimeMillisec = (settings.holdTime?:3).toInteger() * 1000

  if (timeDif < 0) {
    log.debug "Message arrived out of sequence. lastPress: ${startOfPress} and now: ${currentTime}"
    return []  //likely a message sequence issue. Drop this press and wait for another. Probably won't happen...
  }
  else if (timeDif < holdTimeMillisec) {
    log.debug "Button pushed. ${timeDif}"
    return [createEvent(name: "button", value: "pushed", data: [buttonNumber: 1], isStateChange: true)]
  }
  else {
    log.debug "Button held. ${timeDif}"
    return [createEvent(name: "button", value: "held", data: [buttonNumber: 1], isStateChange: true)]
  }
}

private ArrayList createPressEvent() {
  return [createEvent(name: "lastPress", value: now(), data:[buttonNumber: 1], displayed: false)]
}

void push() {
  //  TODO: Does it make sense to behave like a switch :/ ?
  sendEvent(name: "switch", value: "on", displayed: false)
  sendEvent(name: "switch", value: "off", displayed: false)
  sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], isStateChange: true)
}

void on() {
  push()
}

void off() {
  push()
}

void initialize() {
  //  Configure the initial state.
  sendEvent(name: "numberOfButtons", value: 1, displayed: false)
}

void installed() {
	initialize()
}

void updated() {
	initialize()
}
