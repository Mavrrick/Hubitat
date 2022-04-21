/**
 *  Tuya Zigbee Valve driver for Hubitat Elevation
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
 *  ver. 1.0.0 2022-04-21 kkossev - inital version
 *
 */
import groovy.json.*
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

def version() { "1.0.0" }
def timeStamp() {"2022/04/21 10:49 PM"}

metadata {
    definition (name: "Tuya Zigbee Valve", namespace: "kkossev", author: "Krassimir Kossev", importUrl: "https://raw.githubusercontent.com/kkossev/Hubitat/main/Drivers/Tuya%20Zigbee%20Valve/Tuya%20Zigbee%20Valve%20Plug.groovy", singleThreaded: true ) {
        capability "Actuator"    
        //capability "Sensor"
        capability "Valve"
        //capability "Polling"
        //capability "Refresh"
        /*
        command "test", [
            [name:"dpCommand", type: "STRING", description: "Tuya DP Command", constraints: ["STRING"]],
            [name:"dpValue",   type: "STRING", description: "Tuya DP value", constraints: ["STRING"]],
            [name:"dpType",    type: "ENUM",   constraints: ["DP_TYPE_VALUE", "DP_TYPE_BOOL", "DP_TYPE_ENUM"], description: "DP data type"] 
        ]
        */
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0003,0004,0005,0006,E000,E001,0000", outClusters:"0019,000A",     model:"TS0001", manufacturer:"_TZ3000_iedbgyxt"     // https://community.hubitat.com/t/generic-zigbee-3-0-valve-not-getting-fingerprint/92614
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0004,0005,0006,E000,E001", outClusters:"0019,000A",     model:"TS0001", manufacturer:"_TZ3000_o4cjetlm"     // https://community.hubitat.com/t/water-shutoff-valve-that-works-with-hubitat/32454/59?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0004,0005,EF00",                outClusters:"0019,000A",     model:"TS0601", manufacturer:"_TZE200_vrjkcam9"     // https://community.hubitat.com/t/tuya-zigbee-water-gas-valve/78412?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0004,0005,0006",                outClusters:"0019",          model:"TS0011", manufacturer:"_TYZB01_rifa0wlb"     // https://community.hubitat.com/t/tuya-zigbee-water-gas-valve/78412 
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0006",                     outClusters:"0003,0006,0004",model:"TS0001", manufacturer:"_TYZB01_4tlksk8a"     // unknown 
   
    }
    
    preferences {
        input (name: "logEnable", type: "bool", title: "<b>Debug logging</b>", description: "<i>Debug information, useful for troubleshooting. Recommended value is <b>false</b></i>", defaultValue: false)
        input (name: "txtEnable", type: "bool", title: "<b>Description text logging</b>", description: "<i>Display measured values in HE log page. Recommended value is <b>true</b></i>", defaultValue: true)
/*        
        input (name: "autoPollingEnabled", type: "bool", title: "<b>Automatic polling</b>", description: "<i>Enable outlet automatic polling for power, voltage, amperage, energy and switch state. Recommended value is <b>true</b></i>", defaultValue: true)
        if (autoPollingEnabled?.value==true) {
            input (name: "pollingInterval", type: "number", title: "<b>Polling interval</b>, seconds", description: "<i>The time period when the smart plug will be polled for power, voltage and amperage readings. Recommended value is <b>60 seconds</b></i>", 
                   range: "10..3600", defaultValue: defaultPollingInterval)
        }
*/
    }
}

// Constants
@Field static final Integer presenceCountTreshold = 3
@Field static final Integer defaultPollingInterval = 60
@Field static final Integer debouncingTimer = 300
@Field static final Integer digitalTimer = 1000
@Field static final Integer refreshTimer = 3000
@Field static String UNKNOWN = "UNKNOWN"


def parse(String description) {
    if (logEnable==true) {log.debug "description is $description"}
    checkDriverVersion()
    //setPresent()
    if (isTuyaE00xCluster(description) == true || otherTuyaOddities(description) == true) {
        return null
    }
    def event = [:]
    try {
        event = zigbee.getEvent(description)
    }
    catch ( e ) {
        log.warn "exception caught while parsing description:  ${description}"
        //return null
    }
    if (event) {
        if (event.name ==  "switch" ) {
            switchEvent( event.value )
        }
        else {
            if (txtEnable) {log.warn "received <b>unhandled event</b> ${event.name} = $event.value"} 
        }
        return null //event
    }
    else {
        //List result = []
        def descMap = [:]
        try {
            descMap = zigbee.parseDescriptionAsMap(description)
        }
        catch ( e ) {
            log.warn "exception caught while parsing descMap:  ${descMap}"
            //return null
        }
        if (logEnable) {log.debug "Desc Map: $descMap"}
        if (descMap.attrId != null ) {
            // attribute report received
            List attrData = [[cluster: descMap.cluster ,attrId: descMap.attrId, value: descMap.value, status: descMap.status]]
            descMap.additionalAttrs.each {
                attrData << [cluster: descMap.cluster, attrId: it.attrId, value: it.value, status: it.status]
            }
            attrData.each {
                def map = [:]
                if (it.status == "86") {
                    disableUnsupportedAttribute(descMap.cluster, it.attrId)
                }
                else if (it.value && it.cluster == "0B04" && it.attrId == "050B") {
                        powerEvent(zigbee.convertHexToInt(it.value)/powerDiv)
                        if (state.lastPower != zigbee.convertHexToInt(it.value)/powerDiv ) {
                            if (logEnable) {log.trace "power changed from <b>${state.lastPower}</b> to <b>${zigbee.convertHexToInt(it.value)/powerDiv}</b>"}
                            state.lastPower = zigbee.convertHexToInt(it.value)/powerDiv
                        }
                }
                else if ( it.cluster == "0000" && it.attrId in ["0001", "FFE0", "FFE1", "FFE2", "FFE4", "FFFE", "FFDF"]) {
                    if (logEnable) {log.debug "Tuya specific attribute ${it.attrId} reported: ${it.value}" }    // not tested
                }
                else {
                    if (logEnable==true) log.warn "Unprocessed attribute report: cluster=${it.cluster} attrId=${it.attrId} value=${it.value} status=${it.status} data=${descMap.data}"
                }
            } // for each attribute
        } // if attribute report
        else if (descMap.profileId == "0000") { //zdo
            parseZDOcommand(descMap)
        } 
        else if (descMap.clusterId != null && descMap.profileId == "0104") { // ZHA global command
            parseZHAcommand(descMap)
        } 
        else {
            if (logEnable==true)  log.warn "Unprocesed unknown command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
        }
        return null //result
    } // descMap
}

def switchEvent( value ) {
    if (value == 'on') value = 'open'
    else if (value == 'off') value = 'closed'
    else value = 'unknown'

    def map = [:] 
    boolean bWasChange = false
    /*
    if (state.switchDebouncing==true && value==state.lastSwitchState) {    // some plugs send only catchall events, some only readattr reports, but some will fire both...
        if (logEnable) {log.debug "Ignored duplicated switch event for model ${state.model}: ${description}"} 
        runInMillis( debouncingTimer, switchDebouncingClear)
        return null
    }
    */
    map.type = state.isDigital == true ? "digital" : "physical"
    if (state.lastSwitchState != value ) {
        bWasChange = true
        if (logEnable) {log.debug "Valve  state changed from <b>${state.lastSwitchState}</b> to <b>${value}</b>"}
        if (autoPollingEnabled == true) {
            runInMillis(5000, pollSwitch)
            runIn( pollingInterval, autoPoll) // restart polling interval timer
        }
        state.switchDebouncing = true
        state.lastSwitchState = value
        runInMillis( debouncingTimer, switchDebouncingClear)
    }
    map.name = "valve"
    map.value = value
    if (state.isRefreshRequest == true || state.model == "TS0601") {
        map.descriptionText = "${device.displayName} valve is ${value}"
    }
    else {
        map.descriptionText = "${device.displayName} was ${value} [${map.type}]"
    }
    if (optimizations==false || bWasChange==true ) 
    {
        if (txtEnable) {log.info "${map.descriptionText}"}
        sendEvent(map)
    }
    clearIsDigital()
}


def parseZDOcommand( Map descMap ) {
    switch (descMap.clusterId) {
        case "0006" :
            if (logEnable) log.info "Received match descriptor request, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Input cluster count:${descMap.data[5]} Input cluster: 0x${descMap.data[7]+descMap.data[6]})"
            break
        case "0013" : // device announcement
            if (logEnable) log.info "Received device announcement, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Device network ID: ${descMap.data[2]+descMap.data[1]}, Capability Information: ${descMap.data[11]})"
            break
        case "8004" : // simple descriptor response
            if (logEnable) log.info "Received simple descriptor response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, status:${descMap.data[1]}, lenght:${hubitat.helper.HexUtils.hexStringToInt(descMap.data[4])}"
            parseSimpleDescriptorResponse( descMap )
            break
        case "8005" : // endpoint response
            if (logEnable) log.info "Received endpoint response: cluster: ${descMap.clusterId} (endpoint response) endpointCount = ${ descMap.data[4]}  endpointList = ${descMap.data[5]}"
            break
        case "8021" : // bind response
            if (logEnable) log.info "Received bind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1]=="00" ? 'Success' : '<b>Failure</b>'})"
            break
        case "8038" : // Management Network Update Notify
            if (logEnable) log.info "Received Management Network Update Notify, data=${descMap.data}"
            break
        default :
            if (logEnable) log.warn "Unprocessed ZDO command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
    }
}

def parseSimpleDescriptorResponse(Map descMap) {
    //log.info "Received simple descriptor response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, status:${descMap.data[1]}, lenght:${hubitat.helper.HexUtils.hexStringToInt(descMap.data[4])}"
    if (logEnable==true) log.info "Endpoint: ${descMap.data[5]} Application Device:${descMap.data[9]}${descMap.data[8]}, Application Version:${descMap.data[10]}"
    def inputClusterCount = hubitat.helper.HexUtils.hexStringToInt(descMap.data[11])
    def inputClusterList = ""
    for (int i in 1..inputClusterCount) {
        inputClusterList += descMap.data[13+(i-1)*2] + descMap.data[12+(i-1)*2] + ","
    }
    inputClusterList = inputClusterList.substring(0, inputClusterList.length() - 1)
    if (logEnable==true) log.info "Input Cluster Count: ${inputClusterCount} Input Cluster List : ${inputClusterList}"
    if (getDataValue("inClusters") != inputClusterList)  {
        if (logEnable==true) log.warn "inClusters=${getDataValue('inClusters')} differs from inputClusterList:${inputClusterList} - will be updated!"
        updateDataValue("inClusters", inputClusterList)
    }
    
    def outputClusterCount = hubitat.helper.HexUtils.hexStringToInt(descMap.data[12+inputClusterCount*2])
    def outputClusterList = ""
    for (int i in 1..outputClusterCount) {
        outputClusterList += descMap.data[14+inputClusterCount*2+(i-1)*2] + descMap.data[13+inputClusterCount*2+(i-1)*2] + ","
    }
    outputClusterList = outputClusterList.substring(0, outputClusterList.length() - 1)
    if (logEnable==true) log.info "Output Cluster Count: ${outputClusterCount} Output Cluster List : ${outputClusterList}"
    if (getDataValue("outClusters") != outputClusterList)  {
        if (logEnable==true) log.warn "outClusters=${getDataValue('outClusters')} differs from outputClusterList:${outputClusterList} -  will be updated!"
        updateDataValue("outClusters", outputClusterList)
    }
}

def disableUnsupportedAttribute(String clusterId, String attrId) {
    switch (clusterId) {
        case "0006" :    // Switch
            if (logEnable==true) log.warn "Switch polling is not supported -> Switch polling will be diabled."
            state.switchPollingSupported = false
            break
        default :
            if (logEnable==true) log.warn "Read attribute response: unsupported Attributte ${attrId} cluster ${clusterId}"
            break
    }
}

def parseZHAcommand( Map descMap) {
    switch (descMap.command) {
        case "01" : //read attribute response. If there was no error, the successful attribute reading would be processed in the main parse() method.
            def status = descMap.data[2]
            def attrId = descMap.data[1] + descMap.data[0] 
            if (status == "86") {
                disableUnsupportedAttribute(descMap.clusterId, attrId)
                if (logEnable==true) log.trace "descMap = ${descMap}"
            }
            else {
                switch (descMap.clusterId) {
                    case "EF00" :
                        //if (logEnable==true) log.warn "Tuya cluster read attribute response: code ${status} Attributte ${attrId} cluster ${descMap.clusterId} data ${descMap.data}"
                        def attribute = getAttribute(descMap.data)
                        def value = getAttributeValue(descMap.data)
                        //if (logEnable==true) log.trace "attribute=${attribute} value=${value}"
                        def map = [:]
                        def cmd = /*descMap.data[0]+*/ descMap.data[2]
                        switch (cmd) { // code : descMap.data[2]    ; attrId = descMap.data[1] + descMap.data[0] 
                            case "01" : // switch
                                switchEvent(value==0 ? "off" : "on")
                                break
                            case "11" : // Energy
                                energyEvent(value/100)
                                break
                            case "12" : // Amperage
                                amperageEvent(value/1000)
                                break
                            case "13" : // Power
                                powerEvent(value/10)
                                break
                            case "14" : // Voltage
                                voltageEvent(value/10)
                                break
                            case "65" : // Voltage HOCH
                                voltageEvent((zigbee.convertHexToInt(descMap.data[7]) | zigbee.convertHexToInt(descMap.data[6]) << 8) / 10)
                                break
                            case "66" : // Amperage HOCH
                                amperageEvent((zigbee.convertHexToInt(descMap.data[8]) | zigbee.convertHexToInt(descMap.data[7]) << 8) / 1000)
                                break
                            case "67" : // hochActivePower: 103
                                powerEvent((zigbee.convertHexToInt(descMap.data[8]) | zigbee.convertHexToInt(descMap.data[7]) << 8) / 10)
                                break
                            case "69" : // hochTemperature: 105
                                log.info "temperature is ${(zigbee.convertHexToInt(descMap.data[9]))}"
                                break
                            case "09" : // hochCountdownTimer: 9
                            case "1A" : // hochFaultCode: 26
                            case "1B" : // hochRelayStatus: 27 (power recovery behaviour)
                            case "1D" : // hochChildLock: 29
                            case "68" : // hochLeakageCurrent: 104
                            case "6A" : // hochRemainingEnergy: 106
                            case "6B" : // "recharge energy" : 107
                            case "6C" : // hochCostParameters: 108 (non-zero)
                            case "6D" : // hochLeakageParameters: 109 (non-zero)
                            case "6E" : // hochVoltageThreshold: 110 (non-zero)
                            case "6F" : // hochCurrentThreshold: 111 (non-zero)
                            case "70" : // hochTemperatureThreshold: 112 (non-zero)
                            case "71" : // hochTotalActivePower: 113
                            case "72" : // hochEquipmentNumberType: 114
                            case "73" : //: "clear energy",115
                            case "74" : // hochLocking: 116  (test button pressed)
                            case "75" : // hochTotalReverseActivePower: 117
                            case "76" : // hochHistoricalVoltage: 118
                            case "77" : // hochHistoricalCurrent: 119
                                log.trace "cmd = ${cmd}  value = ${(zigbee.convertHexToInt(descMap.data[7]) | zigbee.convertHexToInt(descMap.data[6]) << 8)}"
                                break
                            default :
                                if (logEnable==true) log.warn "Tuya unknown attribute: ${descMap.data[0]}${descMap.data[1]}=${descMap.data[2]}=${descMap.data[3]}${descMap.data[4]} data.size() = ${descMap.data.size()} value: ${value}}"
                                if (logEnable==true) log.warn "map= ${descMap}"
                                break
                        }
                        break
                    default :
                        if (logEnable==true) log.warn "Read attribute response: unknown status code ${status} Attributte ${attrId} cluster ${descMap.clusterId}"
                        break
                } // switch (descMap.clusterId)
            }  //command is read attribute response
            break
        case "07" : // Configure Reporting Response
            if (logEnable==true) log.info "Received Configure Reporting Response for cluster:${descMap.clusterId} , data=${descMap.data} (Status: ${descMap.data[0]=="00" ? 'Success' : '<b>Failure</b>'})"
            // Status: Unreportable Attribute (0x8c)
            break
        case "0B" : // ZCL Default Response
            def status = descMap.data[1]
            if (status != "00") {
                switch (descMap.clusterId) {
                    case "0006" : // Switch state
                        if (logEnable==true) log.warn "Switch state is not supported -> Switch polling will be disabled."
                        state.switchPollingSupported = false
                        break
                    default :
                        if (logEnable==true) log.info "Received ZCL Default Response to Command ${descMap.data[0]} for cluster:${descMap.clusterId} , data=${descMap.data} (Status: ${descMap.data[1]=="00" ? 'Success' : '<b>Failure</b>'})"
                        break
                }
            }
            break
        case "24" :    // Tuya time sync
            log.trace "Tuya time sync"
                        if (descMap?.clusterInt==0xEF00 && descMap?.command == "24") {        //getSETTIME
                            if (settings?.logEnable) log.debug "${device.displayName} time synchronization request from device, descMap = ${descMap}"
                            def offset = 0
                            try {
                                offset = location.getTimeZone().getOffset(new Date().getTime())
                                //if (settings?.logEnable) log.debug "${device.displayName} timezone offset of current location is ${offset}"
                            }
                            catch(e) {
                                if (settings?.logEnable) log.error "${device.displayName} cannot resolve current location. please set location in Hubitat location setting. Setting timezone offset to zero"
                            }
                            def cmds = zigbee.command(0xEF00, 0x24, "0008" +zigbee.convertToHexString((int)(now()/1000),8) +  zigbee.convertToHexString((int)((now()+offset)/1000), 8))
                            if (settings?.logEnable) log.trace "${device.displayName} now is: ${now()}"  // KK TODO - convert to Date/Time string!        
                            if (settings?.logEnable) log.debug "${device.displayName} sending time data : ${cmds}"
                            cmds.each{ sendHubCommand(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)) }
                            return
                        }
            break
        default :
            if (logEnable==true) log.warn "Unprocessed global command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
    }
}

private String getAttribute(ArrayList _data) {
    String retValue = ""
    if (_data.size() >= 5) {
        if (_data[2] == "01" && _data[3] == "01" && _data[4] == "00") {
            retValue = "switch"
        }
        else if (_data[2] == "02" && _data[3] == "02" && _data[4] == "00") {
            retValue = "level"
        }
    }
    return retValue
}

private int getAttributeValue(ArrayList _data) {
    int retValue = 0
    try {    
        if (_data.size() >= 6) {
            int dataLength = zigbee.convertHexToInt(_data[5]) as Integer
            int power = 1;
            for (i in dataLength..1) {
                retValue = retValue + power * zigbee.convertHexToInt(_data[i+5])
                power = power * 256
            }
        }
    }
    catch(e) {
        log.error "Exception caught : data = ${_data}"
    }
    return retValue
}

def close() {
    state.isDigital = true
    if (logEnable) {log.debug "${device.displayName} closing"}
    def cmds = zigbee.off()
    if (state.model == "TS0601") {
        cmds = zigbee.command(0xEF00, 0x0, "00010101000100")
    }
    runInMillis( digitalTimer, clearIsDigital)
    return cmds
}

def open() {
    state.isDigital = true
    if (logEnable) {log.debug "${device.displayName} opening"}
    def cmds = zigbee.on()
    if (state.model == "TS0601") {
        cmds = zigbee.command(0xEF00, 0x0, "00010101000101")
    }
    runInMillis( digitalTimer, clearIsDigital)
    return cmds
}

def clearIsDigital() { state.isDigital = false }

def isRefreshRequestClear() { state.isRefreshRequest = false }

def switchDebouncingClear() { state.switchDebouncing = false }



// * PING is used by Device-Watch in attempt to reach the Device
def ping() {
    return refresh()
}

def pollSwitch() {
    if (logEnable) {log.debug "pollSwitch().."}
    List<String> cmds = []
    cmds += cmds = zigbee.onOffRefresh()  
    state.isRefreshRequest = true
    runInMillis( refreshTimer, isRefreshRequestClear)       // 3 seconds
    return cmds
}


// Sends refresh / readAttribute commands to the device
def poll( refreshAll = false ) {
    if (logEnable) {log.trace "polling.. refreshAll is ${refreshAll}"}
    checkDriverVersion()
    List<String> cmds = []
    if (state.switchPollingSupported == true && refreshAll == true ) {
        cmds = zigbee.onOffRefresh()                            // switch - polled only on full Refresh
    }
    state.isRefreshRequest = true
    runInMillis( refreshTimer, isRefreshRequestClear)           // 3 seconds
    return cmds
}


def refresh() {
    if (logEnable) {log.debug "refresh()..."}
    poll( true )
}

def autoPoll() {
    if (logEnable) {log.debug "autoPoll()"}
    checkIfNotPresent()
    if (autoPollingEnabled?.value == true) {
        if ( pollingInterval != null ) 
            runIn( pollingInterval, autoPoll)
        else
            runIn( defaultPollingInterval, autoPoll)
    }
    if (optimizations == true) 
        poll( refreshAll = false )
    else 
        poll( refreshAll = true )
}

def tuyaBlackMagic() {
    return zigbee.readAttribute(0x0000, [0x0004, 0x000, 0x0001, 0x0005, 0x0007, 0xfffe], [:], delay=200)    // Cluster: Basic, attributes: Man.name, ZLC ver, App ver, Model Id, Power Source, attributeReportingStatus
}

/*
    configure() method is called: 
       *  unconditionally during the initial pairing, immediately after Installed() method
       *  when Initialize button is pressed
       *  from updated() when preferencies are saved
*/
def configure() {
    if (txtEnable==true) log.info " configure().."
    List<String> cmds = []
    cmds += tuyaBlackMagic()
    cmds += refresh()
    cmds += zigbee.onOffConfig()
    sendZigbeeCommands(cmds)
}


// This method is called when the preferences of a device are updated.
def updated(){
    if (txtEnable==true) log.info "Updating ${device.getLabel()} (${device.getName()}) model ${state.model} presence: ${device.currentValue("presence")} AlwaysOn is <b>${alwaysOn}</b> "
    if (txtEnable==true) log.info "Debug logging is <b>${logEnable}</b> Description text logging is  <b>${txtEnable}</b>"
    if (logEnable==true) {
        runIn(/*1800*/86400, logsOff)    // turn off debug logging after /*30 minutes*/24 hours
        if (txtEnable==true) log.info "Debug logging will be automatically switched off after 24 hours"
    }
    else {
        unschedule(logsOff)
    }

    if (autoPollingEnabled?.value==true) {
        if ( pollingInterval != null ) {
            runIn( pollingInterval, autoPoll)
            if (txtEnable==true) log.info "Auto polling is <b>enabled</b>, polling interval is ${pollingInterval} seconds"
        }
        else {
            runIn( defaultPollingInterval, autoPoll)
        }
    }
    else {
        unschedule(autoPoll)
        if (txtEnable==true) log.info "Auto polling is <b>disabled</b>"
    }
    if (txtEnable==true) log.info "configuring the switch and energy reporting.."
    configure()
}



void initializeVars( boolean fullInit = true ) {
    if (txtEnable==true) log.info "${device.displayName} InitializeVars()... fullInit = ${fullInit}"
    if (fullInit == true ) {
        state.clear()
        state.driverVersion = driverVersionAndTimeStamp()
    }
    
    state.packetID = 0
    state.rxCounter = 0
    state.txCounter = 0
    
    if (fullInit == true || state.lastSwitchState == null) state.lastSwitchState = "unknown"
    if (fullInit == true || state.lastPresenceState == null) state.lastPresenceState = "unknown"
    if (fullInit == true || state.notPresentCounter == null) state.notPresentCounter = 0
    if (fullInit == true || state.isDigital == null) state.isDigital = true
    if (fullInit == true || state.isRefreshRequest == null) state.isRefreshRequest = true
    if (fullInit == true || device.getDataValue("logEnable") == null) device.updateSetting("logEnable", false)
    if (fullInit == true || device.getDataValue("txtEnable") == null) device.updateSetting("txtEnable", true)


    def mm = device.getDataValue("model")
    if ( mm != null) {
        state.model = mm
        if (logEnable==true) log.trace " model = ${state.model}"
    }
    else {
        if (txtEnable==true) log.warn " Model not found, please re-pair the device!"
        state.model = UNKNOWN
    }
    def ep = device.getEndpointId()
    if ( ep  != null) {
        state.destinationEP = ep
        if (logEnable==true) log.trace " destinationEP = ${state.destinationEP}"
    }
    else {
        if (txtEnable==true) log.warn " Destination End Point not found, please re-pair the device!"
        state.destinationEP = "01"    // fallback
    }    
}

def driverVersionAndTimeStamp() {version()+' '+timeStamp()}

def checkDriverVersion() {
    if (state.driverVersion != null && driverVersionAndTimeStamp() == state.driverVersion) {
        //log.trace "driverVersion is the same ${driverVersionAndTimeStamp()}"
    }
    else {
        if (txtEnable==true) log.debug "updating the settings from the current driver version ${state.driverVersion} to the new version ${driverVersionAndTimeStamp()}"
        initializeVars( fullInit = false ) 
        state.driverVersion = driverVersionAndTimeStamp()
    }
}

def logInitializeRezults() {
    if (logEnable==true) log.info "${device.displayName} switchPollingSupported  = ${state.switchPollingSupported}"
    if (logEnable==true) log.info "${device.displayName} Initialization finished"
}

def initialize() {
    if (txtEnable==true) log.info "${device.displayName} Initialize()..."
    unschedule()
    initializeVars()
    updated()            // calls also configure()
    runIn( 12, logInitializeRezults)
}

// This method is called when the device is first created.
def installed() {
    if (txtEnable==true) log.info "${device.displayName} Installed()..."
    initializeVars()
    runIn( 5, initialize)
    if (logEnable==true) log.debug "calling initialize() after 5 seconds..."
    // HE will autoomaticall call configure() method here
}

void uninstalled() {
    if (logEnable==true) log.info "${device.displayName} Uninstalled()..."
    unschedule()     //Unschedule any existing schedules
}


// called when any event was received from the Zigbee device in parse() method..
def setPresent() {
    if (state.lastPresenceState != "present") {
    	sendEvent(name: "presence", value: "present") 
        state.lastPresenceState = "present"
    }
    state.notPresentCounter = 0
}

// called from autoPoll()
def checkIfNotPresent() {
    if (state.notPresentCounter != null) {
        state.notPresentCounter = state.notPresentCounter + 1
        if (state.notPresentCounter > presenceCountTreshold) {
            if (state.lastPresenceState != "not present") {
    	        sendEvent(name: "presence", value: "not present")
                state.lastPresenceState = "not present"
                if (logEnable==true) log.warn "not present!"
            }
        }
    }
}

private getCLUSTER_TUYA()       { 0xEF00 }
private getSETDATA()            { 0x00 }
private getSETTIME()            { 0x24 }

private getPACKET_ID() {
    state.packetID = ((state.packetID ?: 0) + 1 ) % 65536
    return zigbee.convertToHexString(state.packetID, 4)
}

private sendTuyaCommand(dp, dp_type, fncmd) {
    ArrayList<String> cmds = []
    cmds += zigbee.command(CLUSTER_TUYA, SETDATA, PACKET_ID + dp + dp_type + zigbee.convertToHexString((int)(fncmd.length()/2), 4) + fncmd )
    if (settings?.logEnable) log.trace "${device.displayName} sendTuyaCommand = ${cmds}"
    if (state.txCounter != null) state.txCounter = state.txCounter + 1
    return cmds
}

void sendZigbeeCommands(List<String> cmds) {
    if (logEnable) {log.trace "${device.displayName} sendZigbeeCommands received : ${cmds}"}
	sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value:"false",type:"bool"])
}

boolean isTuyaE00xCluster( String description )
{
    if(description.indexOf('cluster: E000') >= 0 || description.indexOf('cluster: E001') >= 0) {
        if (logEnable) log.debug " Tuya cluster: E000 or E001 - don't know how to handle it, skipping it for now..."
        return true
    }
    else
        return false
}

boolean otherTuyaOddities( String description )
{
    if(description.indexOf('cluster: 0000') >= 0 || description.indexOf('attrId: 0004') >= 0) {
        if (logEnable) log.debug " other Tuya oddities - don't know how to handle it, skipping it for now..."
        return true
    }
    else
        return false
}

def test( dpCommand, dpValue, dpTypeString ) {
    ArrayList<String> cmds = []
    def dpType   = dpTypeString=="DP_TYPE_VALUE" ? DP_TYPE_VALUE : dpTypeString=="DP_TYPE_BOOL" ? DP_TYPE_BOOL : dpTypeString=="DP_TYPE_ENUM" ? DP_TYPE_ENUM : null
    def dpValHex = dpTypeString=="DP_TYPE_VALUE" ? zigbee.convertToHexString(dpValue as int, 8) : dpValue
    log.warn " sending TEST command=${dpCommand} value=${dpValue} ($dpValHex) type=${dpType}"
    sendZigbeeCommands( sendTuyaCommand(dpCommand, dpType, dpValHex) )
}    

