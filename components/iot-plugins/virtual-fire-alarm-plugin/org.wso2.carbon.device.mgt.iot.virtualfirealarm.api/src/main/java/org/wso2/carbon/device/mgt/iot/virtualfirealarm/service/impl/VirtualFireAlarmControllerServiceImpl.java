/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
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

package org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.certificate.mgt.core.dto.SCEPResponse;
import org.wso2.carbon.certificate.mgt.core.exception.KeystoreException;
import org.wso2.carbon.certificate.mgt.core.service.CertificateManagementService;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.mgt.analytics.data.publisher.AnalyticsDataRecord;
import org.wso2.carbon.device.mgt.analytics.data.publisher.exception.DeviceManagementAnalyticsException;
import org.wso2.carbon.device.mgt.analytics.data.publisher.service.DeviceAnalyticsService;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.extensions.feature.mgt.annotations.Feature;
import org.wso2.carbon.device.mgt.iot.controlqueue.mqtt.MqttConfig;
import org.wso2.carbon.device.mgt.iot.controlqueue.xmpp.XmppConfig;
import org.wso2.carbon.device.mgt.iot.exception.DeviceControllerException;
import org.wso2.carbon.device.mgt.iot.sensormgt.SensorDataManager;
import org.wso2.carbon.device.mgt.iot.sensormgt.SensorRecord;
import org.wso2.carbon.device.mgt.iot.service.IoTServerStartupListener;
import org.wso2.carbon.device.mgt.iot.transport.TransportHandlerException;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.dto.DeviceData;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.dto.SensorData;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.exception.VirtualFireAlarmException;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.transport.VirtualFireAlarmMQTTConnector;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.transport.VirtualFireAlarmXMPPConnector;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.util.SecurityManager;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.util.VirtualFireAlarmServiceUtils;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.util.scep.ContentType;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.impl.util.scep.SCEPOperation;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.plugin.constants.VirtualFireAlarmConstants;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class consists the functions/APIs specific to the "actions" of the VirtualFirealarm device-type. These APIs
 * include the ones that are used by the [Device] to contact the server (i.e: Enrollment & Publishing Data) and the
 * ones used by the [Server/Owner] to contact the [Device] (i.e: sending control signals). This class also initializes
 * the transport 'Connectors' [XMPP & MQTT] specific to the VirtualFirealarm device-type in order to communicate with
 * such devices and to receive messages form it.
 */
@SuppressWarnings("Non-Annoted WebService")
public class VirtualFireAlarmControllerServiceImpl implements VirtualFireAlarmControllerService {

    private static final String XMPP_PROTOCOL = "XMPP";
    private static final String HTTP_PROTOCOL = "HTTP";
    private static final String MQTT_PROTOCOL = "MQTT";
    private static Log log = LogFactory.getLog(VirtualFireAlarmControllerServiceImpl.class);
    // consists of utility methods related to encrypting and decrypting messages
    private SecurityManager securityManager;
    // connects to the given MQTT broker and handles MQTT communication
    private VirtualFireAlarmMQTTConnector virtualFireAlarmMQTTConnector;
    // connects to the given XMPP server and handles XMPP communication
    private VirtualFireAlarmXMPPConnector virtualFireAlarmXMPPConnector;
    // holds a mapping of the IP addresses to Device-IDs for HTTP communication
    private ConcurrentHashMap<String, String> deviceToIpMap = new ConcurrentHashMap<>();

    @POST
    @Path("device/register/{deviceId}/{ip}/{port}")
    public Response registerDeviceIP(@PathParam("deviceId") String deviceId, @PathParam("ip") String deviceIP,
                                     @PathParam("port") String devicePort, @Context HttpServletRequest request) {
        String result;
        if (log.isDebugEnabled()) {
            log.debug("Got register call from IP: " + deviceIP + " for Device ID: " + deviceId);
        }
        String deviceHttpEndpoint = deviceIP + ":" + devicePort;
        deviceToIpMap.put(deviceId, deviceHttpEndpoint);
        result = "Device-IP Registered";
        if (log.isDebugEnabled()) {
            log.debug(result);
        }
        return Response.ok().entity(result).build();
    }

    @POST
    @Path("device/{deviceId}/buzz")
    public Response switchBuzzer(@PathParam("deviceId") String deviceId, @QueryParam("protocol") String protocol,
                                 @FormParam("state") String state) {
        String switchToState = state.toUpperCase();
        if (!switchToState.equals(VirtualFireAlarmConstants.STATE_ON) && !switchToState.equals(
                VirtualFireAlarmConstants.STATE_OFF)) {
            log.error("The requested state change shoud be either - 'ON' or 'OFF'");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String protocolString = protocol.toUpperCase();
        String callUrlPattern = VirtualFireAlarmConstants.BULB_CONTEXT + switchToState;
        if (log.isDebugEnabled()) {
            log.debug("Sending request to switch-bulb of device [" + deviceId + "] via " +
                      protocolString);
        }
        try {
            switch (protocolString) {
                case HTTP_PROTOCOL:
                    String deviceHTTPEndpoint = deviceToIpMap.get(deviceId);
                    if (deviceHTTPEndpoint == null) {
                        return Response.status(Response.Status.PRECONDITION_FAILED).build();
                    }
                    VirtualFireAlarmServiceUtils.sendCommandViaHTTP(deviceHTTPEndpoint, callUrlPattern, true);
                    break;
                case XMPP_PROTOCOL:
                    String xmppResource = VirtualFireAlarmConstants.BULB_CONTEXT.replace("/", "");
                    virtualFireAlarmXMPPConnector.publishDeviceData(deviceId, xmppResource, switchToState);
                    break;
                default:
                    String mqttResource = VirtualFireAlarmConstants.BULB_CONTEXT.replace("/", "");
                    virtualFireAlarmMQTTConnector.publishDeviceData(deviceId, mqttResource, switchToState);
                    break;
            }
            return Response.ok().build();
        } catch (DeviceManagementException | TransportHandlerException e) {
            log.error("Failed to send switch-bulb request to device [" + deviceId + "] via " + protocolString);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @Path("device/{deviceId}/temperature")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestTemperature(@PathParam("deviceId") String deviceId,
                                       @QueryParam("protocol") String protocol) {
        SensorRecord sensorRecord = null;
        String protocolString = protocol.toUpperCase();
        if (log.isDebugEnabled()) {
            log.debug("Sending request to read virtual-firealarm-temperature of device " +
                      "[" + deviceId + "] via " + protocolString);
        }
        try {
            switch (protocolString) {
                case HTTP_PROTOCOL:
                    String deviceHTTPEndpoint = deviceToIpMap.get(deviceId);
                    if (deviceHTTPEndpoint == null) {
                        return Response.status(Response.Status.PRECONDITION_FAILED).build();
                    }
                    String temperatureValue = VirtualFireAlarmServiceUtils.sendCommandViaHTTP(
                            deviceHTTPEndpoint, VirtualFireAlarmConstants.TEMPERATURE_CONTEXT, false);
                    SensorDataManager.getInstance().setSensorRecord(deviceId, VirtualFireAlarmConstants.SENSOR_TEMP,
                                                                    temperatureValue,
                                                                    Calendar.getInstance().getTimeInMillis());
                    break;
                case XMPP_PROTOCOL:
                    String xmppResource = VirtualFireAlarmConstants.TEMPERATURE_CONTEXT.replace("/", "");
                    virtualFireAlarmMQTTConnector.publishDeviceData(deviceId, xmppResource, "");
                    break;
                default:
                    String mqttResource = VirtualFireAlarmConstants.TEMPERATURE_CONTEXT.replace("/", "");
                    virtualFireAlarmMQTTConnector.publishDeviceData(deviceId, mqttResource, "");
                    break;
            }
            sensorRecord = SensorDataManager.getInstance().getSensorRecord(deviceId, VirtualFireAlarmConstants
                    .SENSOR_TEMP);
            return Response.ok().entity(sensorRecord).build();
        } catch (DeviceManagementException | DeviceControllerException | TransportHandlerException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @Path("device/temperature")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pushTemperatureData(final DeviceData dataMsg) {
        String deviceId = dataMsg.deviceId;
        String deviceIp = dataMsg.reply;
        float temperature = dataMsg.value;
        String registeredIp = deviceToIpMap.get(deviceId);
        if (registeredIp == null) {
            log.warn("Unregistered IP: Temperature Data Received from an un-registered IP " +
                     deviceIp + " for device ID - " + deviceId);
            return Response.status(Response.Status.PRECONDITION_FAILED).build();
        } else if (!registeredIp.equals(deviceIp)) {
            log.warn("Conflicting IP: Received IP is " + deviceIp + ". Device with ID " + deviceId +
                     " is already registered under some other IP. Re-registration required");
            return Response.status(Response.Status.CONFLICT).build();
        }
        SensorDataManager.getInstance().setSensorRecord(deviceId, VirtualFireAlarmConstants.SENSOR_TEMP,
                                                        String.valueOf(temperature),
                                                        Calendar.getInstance().getTimeInMillis());
        if (!VirtualFireAlarmServiceUtils.publishToDAS(dataMsg.deviceId, dataMsg.value)) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok().build();
    }

    @GET
    @Path("device/scep")
    public Response scepRequest(@QueryParam("operation") String operation, @QueryParam("message") String message) {
        if (log.isDebugEnabled()) {
            log.debug("Invoking SCEP operation " + operation);
        }
        if (SCEPOperation.GET_CA_CERT.getValue().equals(operation)) {
            if (log.isDebugEnabled()) {
                log.debug("Invoking GetCACert");
            }
            try {
                CertificateManagementService certificateManagementService =
                        VirtualFireAlarmServiceUtils.getCertificateManagementService();
                SCEPResponse scepResponse = certificateManagementService.getCACertSCEP();
                Response.ResponseBuilder responseBuilder;
                switch (scepResponse.getResultCriteria()) {
                    case CA_CERT_FAILED:
                        log.error("CA cert failed");
                        responseBuilder = Response.serverError();
                        break;
                    case CA_CERT_RECEIVED:
                        if (log.isDebugEnabled()) {
                            log.debug("CA certificate received in GetCACert");
                        }
                        responseBuilder = Response.ok(scepResponse.getEncodedResponse(),
                                                      ContentType.X_X509_CA_CERT);
                        break;
                    case CA_RA_CERT_RECEIVED:
                        if (log.isDebugEnabled()) {
                            log.debug("CA and RA certificates received in GetCACert");
                        }
                        responseBuilder = Response.ok(scepResponse.getEncodedResponse(),
                                                      ContentType.X_X509_CA_RA_CERT);
                        break;
                    default:
                        log.error("Invalid SCEP request");
                        responseBuilder = Response.serverError();
                        break;
                }

                return responseBuilder.build();
            } catch (VirtualFireAlarmException e) {
                log.error("Error occurred while enrolling the VirtualFireAlarm device", e);
            } catch (KeystoreException e) {
                log.error("Keystore error occurred while enrolling the VirtualFireAlarm device", e);
            }

        } else if (SCEPOperation.GET_CA_CAPS.getValue().equals(operation)) {

            if (log.isDebugEnabled()) {
                log.debug("Invoking GetCACaps");
            }
            try {
                CertificateManagementService certificateManagementService = VirtualFireAlarmServiceUtils.
                        getCertificateManagementService();
                byte caCaps[] = certificateManagementService.getCACapsSCEP();

                return Response.ok(caCaps, MediaType.TEXT_PLAIN).build();

            } catch (VirtualFireAlarmException e) {
                log.error("Error occurred while enrolling the device", e);
            }
        } else {
            log.error("Invalid SCEP operation " + operation);
        }
        return Response.serverError().build();
    }

    @POST
    @Path("device/scep")
    public Response scepRequestPost(@QueryParam("operation") String operation, InputStream inputStream) {
        if (log.isDebugEnabled()) {
            log.debug("Invoking SCEP operation " + operation);
        }
        if (SCEPOperation.PKI_OPERATION.getValue().equals(operation)) {
            if (log.isDebugEnabled()) {
                log.debug("Invoking PKIOperation");
            }
            try {
                CertificateManagementService certificateManagementService = VirtualFireAlarmServiceUtils.
                        getCertificateManagementService();
                byte pkiMessage[] = certificateManagementService.getPKIMessageSCEP(inputStream);
                return Response.ok(pkiMessage, ContentType.X_PKI_MESSAGE).build();
            } catch (VirtualFireAlarmException e) {
                log.error("Error occurred while enrolling the device", e);
            } catch (KeystoreException e) {
                log.error("Keystore error occurred while enrolling the device", e);
            }
        }
        return Response.serverError().build();
    }

    @Path("device/stats/{deviceId}/sensors/{sensorName}")
    @GET
    @Consumes("application/json")
    @Produces("application/json")
    public Response getVirtualFirealarmStats(@PathParam("deviceId") String deviceId,
                                             @PathParam("sensorName") String sensor,
                                             @QueryParam("username") String user, @QueryParam("from") long from,
                                             @QueryParam("to") long to) {
        try {
            String fromDate = String.valueOf(from);
            String toDate = String.valueOf(to);
            List<SensorData> sensorDatas = new ArrayList<>();
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            DeviceAnalyticsService deviceAnalyticsService = (DeviceAnalyticsService) ctx
                    .getOSGiService(DeviceAnalyticsService.class, null);
            String query = "owner:" + user + " AND deviceId:" + deviceId + " AND deviceType:" +
                           VirtualFireAlarmConstants.DEVICE_TYPE + " AND time : [" + fromDate + " TO " + toDate + "]";
            String sensorTableName = getSensorEventTableName(sensor);
            if (sensorTableName != null) {
                List<AnalyticsDataRecord> records = deviceAnalyticsService.getAllEventsForDevice(sensorTableName, query);
                Collections.sort(records, new Comparator<AnalyticsDataRecord>() {
                    @Override
                    public int compare(AnalyticsDataRecord o1, AnalyticsDataRecord o2) {
                        long t1 = (Long) o1.getValue("time");
                        long t2 = (Long) o2.getValue("time");
                        if (t1 < t2) {
                            return -1;
                        } else if (t1 > t2) {
                            return 1;
                        } else {
                            return 0;
                        }
                    }
                });
                for (AnalyticsDataRecord record : records) {
                    SensorData sensorData = new SensorData();
                    sensorData.setTime((long) record.getValue("time"));
                    sensorData.setValue("" + (float) record.getValue(sensor));
                    sensorDatas.add(sensorData);
                }
                SensorData[] sensorDetails = sensorDatas.toArray(new SensorData[sensorDatas.size()]);
                return Response.ok().entity(sensorDetails).build();
            }
        } catch (DeviceManagementAnalyticsException e) {
            String errorMsg = "Error on retrieving stats on table for sensor name" + sensor;
            log.error(errorMsg);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.status(Response.Status.BAD_REQUEST).build();
    }

    private boolean waitForServerStartup() {
        while (!IoTServerStartupListener.isServerReady()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches the `SecurityManager` specific to this VirtualFirealarm controller service.
     *
     * @return the 'SecurityManager' instance bound to the 'securityManager' variable of this service.
     */
    @SuppressWarnings("Unused")
    public SecurityManager getSecurityManager() {
        return securityManager;
    }

    /**
     * Sets the `securityManager` variable of this VirtualFirealarm controller service.
     *
     * @param securityManager a 'SecurityManager' object that handles the encryption, decryption, signing and validation
     *                        of incoming messages from VirtualFirealarm device-types.
     */
    @SuppressWarnings("Unused")
    public void setSecurityManager(SecurityManager securityManager) {
        this.securityManager = securityManager;
        securityManager.initVerificationManager();
    }

    /**
     * Fetches the `VirtualFireAlarmXMPPConnector` specific to this VirtualFirealarm controller service.
     *
     * @return the 'VirtualFireAlarmXMPPConnector' instance bound to the 'virtualFireAlarmXMPPConnector' variable of
     * this service.
     */
    @SuppressWarnings("Unused")
    public VirtualFireAlarmXMPPConnector getVirtualFireAlarmXMPPConnector() {
        return virtualFireAlarmXMPPConnector;
    }

    /**
     * Sets the `virtualFireAlarmXMPPConnector` variable of this VirtualFirealarm controller service.
     *
     * @param virtualFireAlarmXMPPConnector a 'VirtualFireAlarmXMPPConnector' object that handles all XMPP related
     *                                      communications of any connected VirtualFirealarm device-type
     */
    @SuppressWarnings("Unused")
    public void setVirtualFireAlarmXMPPConnector(
            final VirtualFireAlarmXMPPConnector virtualFireAlarmXMPPConnector) {
        Runnable connector = new Runnable() {
            public void run() {
                if (waitForServerStartup()) {
                    return;
                }
                VirtualFireAlarmControllerServiceImpl.this.virtualFireAlarmXMPPConnector = virtualFireAlarmXMPPConnector;

                if (XmppConfig.getInstance().isEnabled()) {
                    Runnable xmppStarter = new Runnable() {
                        @Override
                        public void run() {
                            virtualFireAlarmXMPPConnector.initConnector();
                            virtualFireAlarmXMPPConnector.connect();
                        }
                    };

                    Thread xmppStarterThread = new Thread(xmppStarter);
                    xmppStarterThread.setDaemon(true);
                    xmppStarterThread.start();
                } else {
                    log.warn("XMPP disabled in 'devicemgt-config.xml'. Hence, VirtualFireAlarmXMPPConnector not started.");
                }
            }
        };
        Thread connectorThread = new Thread(connector);
        connectorThread.setDaemon(true);
        connectorThread.start();
    }

    /**
     * Fetches the `VirtualFireAlarmMQTTConnector` specific to this VirtualFirealarm controller service.
     *
     * @return the 'VirtualFireAlarmMQTTConnector' instance bound to the 'virtualFireAlarmMQTTConnector' variable of
     * this service.
     */
    @SuppressWarnings("Unused")
    public VirtualFireAlarmMQTTConnector getVirtualFireAlarmMQTTConnector() {
        return virtualFireAlarmMQTTConnector;
    }

    /**
     * Sets the `virtualFireAlarmMQTTConnector` variable of this VirtualFirealarm controller service.
     *
     * @param virtualFireAlarmMQTTConnector a 'VirtualFireAlarmMQTTConnector' object that handles all MQTT related
     *                                      communications of any connected VirtualFirealarm device-type
     */
    @SuppressWarnings("Unused")
    public void setVirtualFireAlarmMQTTConnector(
            final VirtualFireAlarmMQTTConnector virtualFireAlarmMQTTConnector) {
        Runnable connector = new Runnable() {
            public void run() {
                if (waitForServerStartup()) {
                    return;
                }
                VirtualFireAlarmControllerServiceImpl.this.virtualFireAlarmMQTTConnector = virtualFireAlarmMQTTConnector;
                if (MqttConfig.getInstance().isEnabled()) {
                    virtualFireAlarmMQTTConnector.connect();
                } else {
                    log.warn("MQTT disabled in 'devicemgt-config.xml'. Hence, VirtualFireAlarmMQTTConnector not started.");
                }
            }
        };
        Thread connectorThread = new Thread(connector);
        connectorThread.setDaemon(true);
        connectorThread.start();
    }

    /**
     * get the event table from the sensor name.
     */
    private String getSensorEventTableName(String sensorName) {
        String sensorEventTableName;
        switch (sensorName) {
            case VirtualFireAlarmConstants.SENSOR_TEMP:
                sensorEventTableName = VirtualFireAlarmConstants.TEMPERATURE_EVENT_TABLE;
                break;
            case VirtualFireAlarmConstants.SENSOR_HUMIDITY:
                sensorEventTableName = VirtualFireAlarmConstants.HUMIDITY_EVENT_TABLE;
                break;
            default:
                sensorEventTableName = null;
        }
        return sensorEventTableName;
    }
}
