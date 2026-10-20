package vn.ptit.iot;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.*;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.*;
import vn.ptit.iot.mqtt.*;
import vn.ptit.iot.service.*;
import vn.ptit.iot.util.LuxConverter;
import vn.ptit.iot.websocket.UpdatesSocket;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring Security + JPA transactions on test-only H2. MQTT and WS edges are mocked.
 * These tests are NOT a substitute for running on MySQL/Mosquitto/ESP8266. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IotIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SensorService sensors;
    @Autowired DeviceService devices;
    @Autowired LuxConverter lux;
    @MockitoBean MqttBridge bridge;
    @MockitoBean CommandPublisher publisher;
    @MockitoBean UpdatesSocket updates;
    @MockitoBean ScheduledExecutorService timers;
    final List<Runnable> scheduled = new ArrayList<>();
    private static final String HASH = "$2b$12$e9HJMy/NmSZReACrwRlCEOpZryl4dpABZYlP1n/SM2/KrX/3OTtk2";

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        String url = "jdbc:h2:mem:iot;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";
        // Read the actual external delivery SQL, not a second independently maintained schema.
        String sql = Files.readString(Path.of("../iot_schema.sql"), StandardCharsets.UTF_8).lines()
            .filter(line -> !line.startsWith("CREATE DATABASE") && !line.startsWith("USE ") && !line.startsWith("SET time_zone"))
            .collect(java.util.stream.Collectors.joining("\n")).replace(" ENGINE=InnoDB", "");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).populate(connection);
        }
        properties.add("spring.datasource.url", () -> url);
    }

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM action_history");
        jdbc.update("DELETE FROM sensor_data");
        jdbc.update("DELETE FROM devices");
        jdbc.update("DELETE FROM sensors");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,password,full_name) VALUES(1,'admin',?,'Administrator')", HASH);
        jdbc.update("INSERT INTO sensors(id,name) VALUES(1,'Temperature'),(2,'Humidity'),(3,'Light')");
        jdbc.update("INSERT INTO devices(id,name,status) VALUES(1,'Light 1','OFF'),(2,'Light 2','OFF')");
        scheduled.clear();
        when(timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
            scheduled.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
    }
    @AfterEach void releasePending() {
        // Virtual timer callbacks: no real sleeps and no command leaks between tests.
        List.copyOf(scheduled).forEach(Runnable::run);
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    String state(int id) { return jdbc.queryForObject("SELECT status FROM devices WHERE id=?", String.class, id); }
    String historyState(long id) { return jdbc.queryForObject("SELECT status FROM action_history WHERE id=?", String.class, id); }
    LocalDateTime created(long id) { return jdbc.queryForObject("SELECT created_at FROM action_history WHERE id=?", LocalDateTime.class, id); }

    @Test void loginCsrfSessionLogoutAndLoginAgain() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ws")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"123456\"}")).andExpect(status().isUnauthorized());
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
            .contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        session = login(session);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin")).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(post("/api/devices/1/control").session(session).contentType("application/json")
            .content("{\"action\":\"ON\"}")).andExpect(status().isForbidden());
        // Pre-login CSRF is invalid after successful authentication.
        mvc.perform(post("/api/devices/1/control").session(session).header("X-CSRF-TOKEN", token)
            .contentType("application/json").content("{\"action\":\"ON\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/devices/1/control").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType("application/json").content("{\"action\":\"ON\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOADING"))
            .andExpect(jsonPath("$.timeoutSeconds").value(5));
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", csrf(session)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        MockHttpSession again = login(new MockHttpSession());
        mvc.perform(get("/api/auth/me").session(again)).andExpect(status().isOk());
    }
    String csrf(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("token").asText();
    }
    MockHttpSession login(MockHttpSession session) throws Exception {
        var response = mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType("application/json").content("{\"username\":\"admin\",\"password\":\"123456\"}"))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) response.getRequest().getSession(false);
    }

    @Test void destroyedHttpSessionClosesItsSocketAndStopsBroadcasts() throws Exception {
        var session = new MockHttpSession();
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("session-socket");
        when(socket.getAttributes()).thenReturn(Map.of(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME, session.getId()));
        var handler = new UpdatesSocket(json);
        handler.afterConnectionEstablished(socket);
        handler.sessionDestroyed(new HttpSessionDestroyedEvent(session));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        handler.broadcast("sensor:update", Map.of());
        verify(socket, never()).sendMessage(any());
    }
    @Test void packetCommitsThreeRowsThenPublishesTheSameRoundedValues() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(count("sensor_data")).isEqualTo(3);
            SensorSnapshot snapshot = call.getArgument(1);
            assertThat(snapshot.temperature()).isEqualTo(28.6);
            assertThat(snapshot.humidity()).isEqualTo(70.1);
            assertThat(snapshot.light()).isEqualTo(lux.convert(650));
            assertThat(snapshot.ids()).hasSize(3);
            return null;
        }).when(updates).broadcast(eq("sensor:update"), any());
        sensors.record(28.55,70.05,650);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT created_at) FROM sensor_data", Integer.class)).isEqualTo(1);
        assertThat(sensors.latest().light()).isEqualTo(lux.convert(650));
        assertThat(sensors.chart().light().get(0).value()).isEqualTo(sensors.latest().light());
        assertThat(sensors.history(3, String.valueOf(lux.convert(650)), null,0,10).totalElements()).isEqualTo(1);
        assertThat(sensors.history(1,"28.6",null,0,10).totalElements()).isEqualTo(1);
    }
    @Test void failedSensorTransactionPublishesNothingAndRollsBackAllRows() {
        jdbc.update("DELETE FROM sensors WHERE id=3");
        assertThatThrownBy(() -> sensors.record(27,60,500)).isInstanceOf(RuntimeException.class);
        assertThat(count("sensor_data")).isZero();
        verifyNoInteractions(updates);
    }
    @Test void emptySnapshotAndChartHaveNoFabricatedData() {
        assertThat(sensors.latest().time()).isNull();
        assertThat(sensors.latest().temperature()).isNull();
        assertThat(sensors.chart().temperature()).isEmpty();
        assertThat(devices.status(1).updatedAt()).isNull();
        assertThat(devices.status(2).status()).isEqualTo(DeviceStatus.OFF);
    }
    @Test void chartIsAlwaysLatestTwentyAndKeepsDistinctRowsWithEqualTimestamps() {
        for (int i=0; i<24; i++) sensors.record(20+i/10.0,60,i+400);
        assertThat(count("sensor_data")).isEqualTo(72);
        var chart = sensors.chart();
        assertThat(chart.temperature()).hasSize(20);
        assertThat(chart.temperature().get(0).value()).isEqualTo(20.4);
        assertThat(chart.temperature().get(19).value()).isEqualTo(22.3);
        assertThat(chart.temperature().stream().map(ChartPoint::id).distinct().count()).isEqualTo(20);
    }
    @Test void loadingIsCommittedBeforeImmediateAckAndSuccessEventFollowsCommit() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            long id = jdbc.queryForObject("SELECT MAX(id) FROM action_history", Long.class);
            assertThat(historyState(id)).isEqualTo("LOADING");
            assertThat(state(1)).isEqualTo("OFF");
            devices.acknowledge(1,DeviceStatus.ON); // Immediate callback, before control() returns.
            return null;
        }).when(publisher).publish(1,DeviceStatus.ON);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state(1)).isEqualTo("ON");
            return null;
        }).when(updates).broadcast(eq("device:status"), any());
        var response = devices.control(1,DeviceStatus.ON,"admin");
        assertThat(response.status()).isEqualTo("LOADING"); // Acceptance, not the ACK result.
        assertThat(historyState(response.id())).isEqualTo("ON");
        assertThat(devices.status(1).updatedAt()).isEqualTo(created(response.id()));
        scheduled.get(0).run(); // Even a cancelled timer that was already queued is harmless.
        assertThat(state(1)).isEqualTo("ON");
        verify(updates,never()).broadcast(eq("device:timeout"),any());
    }
    @Test void eachLedCanSucceedAndReverseIndependently() {
        var first = devices.control(1,DeviceStatus.ON,"admin");
        var second = devices.control(2,DeviceStatus.ON,"admin");
        devices.acknowledge(2,DeviceStatus.ON);
        assertThat(historyState(first.id())).isEqualTo("LOADING");
        assertThat(historyState(second.id())).isEqualTo("ON");
        devices.acknowledge(1,DeviceStatus.ON);
        var off = devices.control(1,DeviceStatus.OFF,"admin");
        devices.acknowledge(1,DeviceStatus.OFF);
        assertThat(historyState(off.id())).isEqualTo("OFF");
        assertThat(state(2)).isEqualTo("ON");
    }
    @Test void oneTimeoutDoesNotAffectTheOtherLedAndRetryGetsNewHistory() {
        var first = devices.control(1,DeviceStatus.ON,"admin");
        var second = devices.control(2,DeviceStatus.ON,"admin");
        devices.acknowledge(2,DeviceStatus.ON);
        scheduled.get(0).run();
        assertThat(historyState(first.id())).isEqualTo("LOADING");
        assertThat(historyState(second.id())).isEqualTo("ON");
        assertThat(state(1)).isEqualTo("OFF");
        assertThat(devices.status(1).updatedAt()).isNull();
        var retry = devices.control(1,DeviceStatus.ON,"admin");
        assertThat(retry.id()).isNotEqualTo(first.id());
        scheduled.get(0).run(); // Old timer cannot remove retry.
        devices.acknowledge(1,DeviceStatus.ON);
        assertThat(historyState(retry.id())).isEqualTo("ON");
    }
    @Test void bothTimeoutThenBothRetry() {
        var a = devices.control(1,DeviceStatus.ON,"admin");
        var b = devices.control(2,DeviceStatus.ON,"admin");
        scheduled.get(0).run(); scheduled.get(1).run();
        assertThat(historyState(a.id())).isEqualTo("LOADING");
        assertThat(historyState(b.id())).isEqualTo("LOADING");
        assertThat(state(1)).isEqualTo("OFF"); assertThat(state(2)).isEqualTo("OFF");
        devices.control(1,DeviceStatus.ON,"admin"); devices.control(2,DeviceStatus.ON,"admin");
        devices.acknowledge(1,DeviceStatus.ON); devices.acknowledge(2,DeviceStatus.ON);
        assertThat(state(1)).isEqualTo("ON"); assertThat(state(2)).isEqualTo("ON");
    }
    @Test void timeoutKeepsPreviousConfirmedTimestampAndCreatedAtIsImmutable() {
        var on = devices.control(1,DeviceStatus.ON,"admin");
        LocalDateTime createdAt = created(on.id());
        devices.acknowledge(1,DeviceStatus.ON);
        var off = devices.control(1,DeviceStatus.OFF,"admin");
        scheduled.get(1).run();
        assertThat(historyState(off.id())).isEqualTo("LOADING");
        assertThat(devices.status(1).status()).isEqualTo(DeviceStatus.ON);
        assertThat(devices.status(1).updatedAt()).isEqualTo(createdAt);
        assertThat(created(on.id())).isEqualTo(createdAt);
        devices.acknowledge(1,DeviceStatus.OFF); // No pending request: ignored.
        assertThat(state(1)).isEqualTo("ON");
    }
    @Test void duplicateStateAndBusyClicksDoNotCreateHistory() {
        assertThatThrownBy(() -> devices.control(1,DeviceStatus.OFF,"admin")).isInstanceOf(RuntimeException.class);
        assertThat(count("action_history")).isZero();
        devices.control(1,DeviceStatus.ON,"admin");
        assertThatThrownBy(() -> devices.control(1,DeviceStatus.ON,"admin")).isInstanceOf(RuntimeException.class);
        assertThat(count("action_history")).isEqualTo(1);
        devices.acknowledge(1,DeviceStatus.OFF); // Mismatched status is not an ACK for ON.
        assertThat(state(1)).isEqualTo("OFF");
    }
    @Test void searchIsParameterizedCombinedAndPagedOnTheServer() {
        jdbc.update("INSERT INTO sensor_data(sensor_id,value,created_at) VALUES(1,28.5,'2024-02-29 12:34:56'),(2,70,'2024-02-29 12:34:56'),(1,28.5,'2024-03-01 00:00:00')");
        assertThat(sensors.history(1,"+28.5","2024-02",0,5).totalElements()).isEqualTo(1);
        assertThat(sensors.history(null,"","2024",0,5).totalElements()).isEqualTo(3);
        assertThat(sensors.history(null,"","2024-02-29 12:34:56",0,10).totalElements()).isEqualTo(2);
        assertThat(sensors.history(null,"","2025",0,15).content()).isEmpty();
        assertThatThrownBy(() -> sensors.history(null,"","2023-02-29",0,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sensors.history(null,"1e2","",0,10)).isInstanceOf(IllegalArgumentException.class);
        for(int i=0;i<18;i++) jdbc.update("INSERT INTO sensor_data(sensor_id,value,created_at) VALUES(3,123,'2026-10-16 12:00:00')");
        var page=sensors.history(3,"123",null,1,5);
        assertThat(page.totalElements()).isEqualTo(18); assertThat(page.totalPages()).isEqualTo(4);
        assertThat(page.page()).isEqualTo(1); assertThat(page.content()).hasSize(5);
        assertThat(page.content().get(0).id()).isGreaterThan(page.content().get(1).id());
        devices.control(1,DeviceStatus.ON,"admin"); devices.acknowledge(1,DeviceStatus.ON);
        devices.control(2,DeviceStatus.ON,"admin"); scheduled.get(1).run();
        assertThat(devices.history(1,DeviceStatus.ON,ActionStatus.ON,null,0,10).content().get(0).username()).isEqualTo("admin");
        assertThat(devices.history(2,DeviceStatus.ON,ActionStatus.LOADING,null,0,10).totalElements()).isEqualTo(1);
        assertThat(devices.history(1,DeviceStatus.OFF,ActionStatus.LOADING,null,0,10).content()).isEmpty();
    }
    @Test void mqttBoundaryRejectsInvalidSensorPacketsAndAcceptsBothAdcEndpoints() {
        var boundary = new MqttBridge(mock(MqttAsyncClient.class), sensors, devices, json, timers,"u","p");
        for(String body : List.of("{}","{\"temperature\":28,\"humidity\":null,\"light\":10}",
             "{\"temperature\":28,\"humidity\":70,\"light\":1024}", "not-json")) {
            boundary.messageArrived("iot/sensors/data",new MqttMessage(body.getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(count("sensor_data")).isZero();
        verifyNoInteractions(updates);
        for(int adc : List.of(0,1023)) boundary.messageArrived("iot/sensors/data",new MqttMessage(
            ("{\"temperature\":28,\"humidity\":70,\"light\":"+adc+"}").getBytes(StandardCharsets.UTF_8)));
        assertThat(count("sensor_data")).isEqualTo(6);
    }
    @Test void commandPublisherUsesOriginalNonRetainedQosZeroTextWithoutOfflineRetry() throws Exception {
        var client = mock(MqttAsyncClient.class);
        var commands = new CommandPublisher(client);
        when(client.isConnected()).thenReturn(false);
        commands.publish(1,DeviceStatus.ON);
        verify(client,never()).publish(anyString(),any(byte[].class),anyInt(),anyBoolean());
        when(client.isConnected()).thenReturn(true);
        commands.publish(2,DeviceStatus.OFF);
        verify(client).publish(eq("iot/devices/command"),eq("2:OFF".getBytes(StandardCharsets.UTF_8)),eq(0),eq(false));
    }
}
