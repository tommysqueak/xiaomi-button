/**
 *  Xiaomi Button
 *
 *  https://github.com/tommysqueak/xiaomi-button
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
 *
 *  Revision History
 *  ==============================================
 *  2017-09-16 - Battery icon - Credit: Power bank by Gregor Cresnar from the Noun Project.
 *  2017-09-16 - Special low level battery warning event.
 *  2017-05-28 - Improved UI - no need for refresh. last check is secondary info so move off hero tile.
 *               Push feedback for in-app button pushes, and so it doesn't look disabled.
 *  2017-05-28 - Use the battery level attribute, instead of custom, from the Battery capability.
 *  2017-05-21 - Cleaned up the activity log, so we're only reporting interesting events.
 *  2017-05-17 - Icon for the showing in the Things list.
 *  2017-05-17 - Code tidy up, reformat and dead code removal.
 *
 *  Previous history:
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
  definition (name: "Xiaomi Button v6", namespace: "tommysqueak", author: "Tom Philip", ocfDeviceType: "x.com.st.d.irblaster", mcdSync: true, runLocally: true, vid: "f3b36157-e8d6-3942-b7b7-3a92ef13693c", mnmn: "SmartThingsCommunity") {
    capability "Button"
    capability "Holdable Button"
    capability "Momentary"
    capability "Battery"
    capability "Configuration"
    //   Health Check https://github.com/constjs/jcdevhandlers/commit/ea275dcf5b6ddfb617104e1f8950dd9f7916e276#diff-898033a1cdc1ae113328ecaeab60a1d6

    attribute "lastPress", "string"
    attribute "lastCheckin", "string"

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
    multiAttributeTile(name:"button", type: "generic", width: 6, height: 4, canChangeIcon: true) {
      tileAttribute ("device.button", key: "PRIMARY_CONTROL") {
        attributeState "push", label: 'pushing', action: "momentary.push", backgroundColor:"#00a0dc"
        attributeState "held", label: 'push', action: "momentary.push", backgroundColor:"#00a0dc"
      }

      tileAttribute("device.battery", key: "SECONDARY_CONTROL") {
				attributeState "default", label:'${currentValue}%', unit:"%", icon:"https://raw.githubusercontent.com/tommysqueak/xiaomi-button/master/icons/battery.png"
			}
    }

    valueTile("lastCheckin", "device.lastCheckin", decoration: "flat", inactiveLabel: false, width: 4, height: 1) {
      state "lastCheckin", label:'Last check-in:\n ${currentValue}'
    }

    standardTile("configure", "device.configure", inactiveLabel: false, decoration: "flat", width: 2, height: 1) {
      state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
    }

    main (["button"])
    details(["button", "lastCheckin", "configure"])
  }
}

def parse(String description) {
  log.debug "Parsing '${description}'"

  def results = []
  if (description?.startsWith('on/off: '))
    results = parseButtonActionMessage(description)
  if (description?.startsWith('catchall:'))
    results = parseCatchAllMessage(description)

  def now = new Date().format("EEE, d MMM yyyy HH:mm:ss",location.timeZone)
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

  def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {  // Special value for low battery alert
		map.value = 1
		map.descriptionText = "Low Battery"
	} else {
		map.value = batteryLevel
	}

  return createEvent(map)
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
  sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], isStateChange: true)
}

void initialize() {
  //  Configure the initial state.
  sendEvent(name: "numberOfButtons", value: 1, displayed: false)
  sendEvent(name: "supportedButtonValues", value: ['pushed', 'held'], displayed: false)
}

void installed() {
	initialize()
}

void updated() {
	initialize()
}
