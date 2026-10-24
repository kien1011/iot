package vn.ptit.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.*;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.*;
import vn.ptit.iot.mqtt.*;
import vn.ptit.iot.repository.*;
import vn.ptit.iot.service.*;
import vn.ptit.iot.websocket.LiveUpdates;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP security, services, JPA, SQL and commits; only transport/clock/timers are controlled. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectIntegrationTest {
    private static final Instant BASE = Instant.parse("2026-10-24T03:30:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceService devices;
    @Autowired SensorService sensors;
    @Autowired MqttMessageHandler mqtt;
    @Autowired DeviceRepository deviceRepository;
    @Autowired UserRepository users;
    @Autowired ActionHistoryRepository history;
    @Autowired TransactionTemplate transactions;
    @MockitoBean MqttConnectionManager connections;
    @MockitoBean MqttCommandPublisher commands;
    @MockitoBean LiveUpdates live;
    @MockitoBean Clock clock;
    @MockitoBean(name = "commandScheduler") ThreadPoolTaskScheduler scheduler;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private final List<Runnable> timers = new ArrayList<>();
    private boolean mysql;

    @BeforeEach void prepare() throws Exception {
        // The exact delivered SQL is also executed by tests (no Hibernate schema generation).
        String url;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) { url = connection.getMetaData().getURL(); }
        mysql = url.startsWith("jdbc:mysql:");
        if (mysql) assertThat(url).contains("iot_control_center_test");
        for (String table : List.of("action_history", "sensor_data", "devices", "sensors", "users")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        String schema = Files.readString(Path.of("../sql/iot_schema.sql")).replaceAll("(?m)^--.*$", "");
        for (String part : schema.split(";")) {
            String statement = part.trim();
            if (statement.isEmpty() || statement.startsWith("CREATE DATABASE") || statement.startsWith("USE ") || statement.startsWith("SET SESSION")) continue;
            jdbc.execute(statement);
        }
        jdbc.update("UPDATE devices SET updated_at = '2026-10-01 00:00:00'");
        time.set(BASE);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
        timers.clear();
        doAnswer(invocation -> {
            timers.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test void bearerTokenLoginLogoutAndRelogin() throws Exception {
        mvc.perform(get("/api/sensor-data/latest")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ws")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        String token = login();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("username").value("admin"))
            .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("token").doesNotExist());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token + "tampered"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "obsolete")))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/devices/1/control").contentType("application/json").content("{\"action\":\"ON\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/devices/1/control").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"action\":\"ON\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("status").value("LOADING")).andExpect(jsonPath("timeoutSeconds").value(5));
        devices.acknowledge(1, DeviceState.ON);
        mvc.perform(get("/api/sensor-data/chart").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("temperature").isEmpty());
        // With a valid subprotocol token, the request reaches the handshake (then lacks Upgrade).
        mvc.perform(get("/ws").header("Sec-WebSocket-Protocol", "bearer, " + token))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/ws").param("token", token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        verify(live).closeForToken(token);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String newToken = login();
        assertThat(newToken).isNotEqualTo(token);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken)).andExpect(status().isOk());
    }

    @Test void tokenExpiresAtDeadlineAndLogoutOnlyRevokesItsOwnToken() throws Exception {
        String first = login();
        String second = login();
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second)).andExpect(status().isOk());
        time.set(BASE.plusSeconds(1799));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second)).andExpect(status().isOk());
        time.set(BASE.plusSeconds(1800));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second)).andExpect(status().isUnauthorized());
        mvc.perform(get("/ws").header("Sec-WebSocket-Protocol", "bearer, " + second)).andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("id").value(1))
            .andExpect(jsonPath("username").value("admin")).andExpect(jsonPath("fullName").value("Administrator"))
            .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("token").isNotEmpty())
            .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test void sqlUsesFiveRealEntitiesAndCheckedBcryptSeed() {
        assertThat(users.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_data", Integer.class)).isZero();
        assertThat(history.count()).isZero();
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .matches("123456", users.findById(1L).orElseThrow().getPassword())).isTrue();
        assertThat(devices.status(1).status()).isEqualTo(DeviceState.OFF);
        assertThat(devices.status(1).updatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(sensors.latest().temperature()).isNull();
        assertThat(sensors.chart().temperature()).isEmpty();
    }

    @Test void onePacketCommitsThreeRowsThenEmitsSamePersistedLuxAndPrecision() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_data", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT created_at) FROM sensor_data", Integer.class)).isEqualTo(1);
            SensorReadingResponse reading = invocation.getArgument(1);
            assertThat(reading).isEqualTo(sensors.latest());
            return null;
        }).when(live).broadcast(eq("sensor:update"), any());
        mqtt.receive("iot/sensors/data", "{\"temperature\":28.56,\"humidity\":70.14,\"light\":68}");
        var reading = sensors.latest();
        assertThat(reading.temperature()).isEqualTo(28.6);
        assertThat(reading.humidity()).isEqualTo(70.1);
        assertThat(reading.light()).isGreaterThan(650);
        assertThat(reading.time()).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 30));
        var found = sensors.history(3, String.format(Locale.ROOT, "%.0f", reading.light()), null, 0, 10);
        assertThat(found.totalElements()).isEqualTo(1);
        assertThat(found.content().get(0).value()).isEqualTo(reading.light());
        assertThat(sensors.chart().light().get(0).value()).isEqualTo(reading.light());
        verify(live).broadcast("sensor:update", reading);
    }

    @Test void invalidMqttIsIgnoredButAdcEndpointsAreAccepted() {
        for (String payload : List.of("bad", "{}", "null", "[]", "{\"temperature\":2,\"humidity\":101,\"light\":2}",
                "{\"temperature\":2,\"humidity\":50,\"light\":-1}", "{\"temperature\":2,\"humidity\":50,\"light\":2.4}")) {
            mqtt.receive("iot/sensors/data", payload);
        }
        assertThat(sensors.latest().temperature()).isNull();
        verifyNoInteractions(live);
        mqtt.receive("iot/sensors/data", "{\"temperature\":2,\"humidity\":50,\"light\":0}");
        mqtt.receive("iot/sensors/data", "{\"temperature\":2,\"humidity\":50,\"light\":1023}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_data", Integer.class)).isEqualTo(6);
    }

    @Test void chartKeepsTwentyInOrderAndHistoryFiltersInDatabase() {
        for (int i = 0; i < 23; i++) {
            time.set(BASE.plusSeconds(i * 2L));
            sensors.record(20 + i, 60, 200 + i);
        }
        var chart = sensors.chart();
        assertThat(chart.temperature()).hasSize(20);
        assertThat(chart.humidity()).hasSize(20);
        assertThat(chart.light()).hasSize(20);
        assertThat(chart.temperature().get(0).value()).isEqualTo(23);
        assertThat(chart.temperature().get(19).value()).isEqualTo(42);
        var historyPage = sensors.history(1, null, "2026-10-24 10:30", 1, 5);
        assertThat(historyPage.totalElements()).isEqualTo(23);
        assertThat(historyPage.totalPages()).isEqualTo(5);
        assertThat(historyPage.page()).isEqualTo(1);
        assertThat(historyPage.content()).hasSize(5);
        assertThat(historyPage.content()).extracting(SensorHistoryResponse::id).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(sensors.history(1, "+28.0", null, 0, 10).totalElements()).isEqualTo(1);
        assertThat(sensors.history(null, null, "2024-02-29", 0, 10).totalElements()).isZero();
        assertThat(sensors.history(null, null, "2027", 0, 10).content()).isEmpty();
        assertThat(sensors.latest().temperature()).isEqualTo(42);
        // With no new packet, the same stored snapshot is retained.
        time.set(BASE.plusSeconds(1000));
        assertThat(sensors.latest().temperature()).isEqualTo(42);
        assertThat(sensors.chart()).isEqualTo(chart);
        sensors.record(43, 60, 224);
        assertThat(sensors.chart().temperature().get(19).value()).isEqualTo(43);
    }

    @Test void immediateAckSeesCommittedLoadingAndWebSocketSeesCommittedSuccess() {
        var original = devices.status(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM action_history", String.class)).isEqualTo("LOADING");
            time.set(BASE.plusSeconds(1));
            mqtt.receive("iot/devices/status", "{\"deviceId\":1,\"status\":\"ON\"}");
            return null;
        }).when(commands).publish(1, DeviceState.ON);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM action_history", String.class)).isEqualTo("SUCCESS");
            assertThat(devices.status(1).status()).isEqualTo(DeviceState.ON);
            return null;
        }).when(live).broadcast(eq("device:status"), any());
        var response = devices.control(1, DeviceState.ON, 1L);
        assertThat(response.status()).isEqualTo(ActionStatus.LOADING);
        assertThat(history.findById(response.id()).orElseThrow().getCreatedAt()).isEqualTo(LocalDateTime.of(2026,10,24,10,30));
        assertThat(devices.status(1).updatedAt()).isEqualTo(LocalDateTime.of(2026,10,24,10,30,1));
        assertThat(devices.status(1).updatedAt()).isAfter(original.updatedAt());
        timers.get(0).run();
        assertThat(history.findById(response.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.SUCCESS);
    }

    @Test void eachCombinationOfTwoIndependentLedsAndRetry() {
        for (boolean success1 : List.of(false, true)) for (boolean success2 : List.of(false, true)) {
            jdbc.update("UPDATE devices SET status='OFF', updated_at='2026-10-01 00:00:00'");
            var first = devices.control(1, DeviceState.ON, 1L);
            var firstTimer = timers.get(timers.size() - 1);
            var second = devices.control(2, DeviceState.ON, 1L);
            var secondTimer = timers.get(timers.size() - 1);
            if (success1) devices.acknowledge(1, DeviceState.ON); else firstTimer.run();
            if (success2) devices.acknowledge(2, DeviceState.ON); else secondTimer.run();
            assertThat(history.findById(first.id()).orElseThrow().getStatus()).isEqualTo(success1 ? ActionStatus.SUCCESS : ActionStatus.FAILED);
            assertThat(history.findById(second.id()).orElseThrow().getStatus()).isEqualTo(success2 ? ActionStatus.SUCCESS : ActionStatus.FAILED);
            assertThat(devices.status(1).status()).isEqualTo(success1 ? DeviceState.ON : DeviceState.OFF);
            assertThat(devices.status(2).status()).isEqualTo(success2 ? DeviceState.ON : DeviceState.OFF);
            if (!success1) assertThat(devices.status(1).updatedAt()).isEqualTo(LocalDateTime.of(2026,10,1,0,0));
            if (!success2) assertThat(devices.status(2).updatedAt()).isEqualTo(LocalDateTime.of(2026,10,1,0,0));
            var retry = devices.control(1, success1 ? DeviceState.OFF : DeviceState.ON, 1L);
            firstTimer.run();
            assertThat(history.findById(retry.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.LOADING);
            devices.acknowledge(1, success1 ? DeviceState.OFF : DeviceState.ON);
            assertThat(history.findById(retry.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.SUCCESS);
        }
    }

    @Test void duplicateAndCurrentStateNeverCreateHistoryAndMismatchDoesNotConfirm() {
        assertThatThrownBy(() -> devices.control(1, DeviceState.OFF, 1L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(history.count()).isZero();
        var first = devices.control(1, DeviceState.ON, 1L);
        assertThatThrownBy(() -> devices.control(1, DeviceState.ON, 1L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(history.count()).isEqualTo(1);
        devices.acknowledge(1, DeviceState.OFF);
        assertThat(history.findById(first.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.LOADING);
        timers.get(0).run();
    }

    @Test void deadlineAndTimeoutCommitHistoryWithoutChangingDeviceTime() {
        var original = devices.status(1);
        var request = devices.control(1, DeviceState.ON, 1L);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(history.findById(request.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.FAILED);
            assertThat(devices.status(1)).isEqualTo(original);
            return null;
        }).when(live).broadcast(eq("device:timeout"), any());
        time.set(BASE.plusSeconds(5));
        devices.acknowledge(1, DeviceState.ON);
        timers.get(0).run();
        assertThat(devices.status(1)).isEqualTo(original);
        assertThat(history.findById(request.id()).orElseThrow().getCreatedAt()).isEqualTo(LocalDateTime.of(2026,10,24,10,30));
        verify(live, times(1)).broadcast("device:timeout", original);
    }

    @Test void databaseFailureRollsBackAndNeverBroadcastsFalseSuccess() {
        var request = devices.control(1, DeviceState.ON, 1L);
        // Force a real constraint failure during the device UPDATE, after history was changed in memory.
        jdbc.execute("ALTER TABLE devices ADD CONSTRAINT test_fail_on CHECK (status='OFF')");
        assertThatThrownBy(() -> devices.acknowledge(1, DeviceState.ON)).isInstanceOf(RuntimeException.class);
        assertThat(history.findById(request.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.LOADING);
        assertThat(devices.status(1).status()).isEqualTo(DeviceState.OFF);
        verifyNoInteractions(live);
        jdbc.execute("ALTER TABLE devices DROP " + (mysql ? "CHECK" : "CONSTRAINT") + " test_fail_on");
        timers.get(0).run();
        assertThat(history.findById(request.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.FAILED);
    }

    @Test void actionHistoryCombinesFiltersAndUsesUserAndDeviceFromForeignKeys() {
        var one = devices.control(1, DeviceState.ON, 1L);
        devices.acknowledge(1, DeviceState.ON);
        devices.control(2, DeviceState.ON, 1L);
        timers.get(1).run();
        var result = devices.history(1, DeviceState.ON, ActionStatus.SUCCESS, "2026-10-24 10:30:00", 0, 5);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).id()).isEqualTo(one.id());
        assertThat(result.content().get(0).deviceName()).isEqualTo("Light 1");
        assertThat(result.content().get(0).username()).isEqualTo("admin");
        assertThat(result.content().get(0).userId()).isEqualTo(1L);
        assertThat(devices.history(1, DeviceState.ON, ActionStatus.FAILED, null, 0, 10).totalElements()).isZero();
        assertThat(devices.history(null, null, null, null, 0, 10).content()).extracting(ActionHistoryResponse::id)
            .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test void invalidSearchReturns400AndValidEmptyYearReturns200() throws Exception {
        String token = login();
        for (String value : List.of("NaN", "Infinity", "1e2", "12,3", "10 lux", "1.")) {
            mvc.perform(get("/api/sensor-data/history").header("Authorization", "Bearer " + token).param("value", value)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/sensor-data/history").header("Authorization", "Bearer " + token).param("time", "2023-02-29")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/devices/action-history").header("Authorization", "Bearer " + token).param("time", "2026-13")).andExpect(status().isBadRequest());
        for (String timeText : List.of("0000", "9999", "2024-02-29"))
            mvc.perform(get("/api/sensor-data/history").header("Authorization", "Bearer " + token).param("time", timeText))
            .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(0)).andExpect(jsonPath("totalPages").value(0));
    }

    @Test void aRealScheduledTimeoutFinishesAfterFiveSeconds() {
        var actualScheduler = new ThreadPoolTaskScheduler();
        actualScheduler.setPoolSize(2); actualScheduler.initialize();
        var service = new DeviceService(deviceRepository, users, history, transactions, actualScheduler,
            Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")), commands, live);
        try {
            long start = System.nanoTime();
            var request = service.control(1, DeviceState.ON, 1L);
            assertThat(history.findById(request.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.LOADING);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(() ->
                assertThat(history.findById(request.id()).orElseThrow().getStatus()).isEqualTo(ActionStatus.FAILED));
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
            assertThat(devices.status(1).status()).isEqualTo(DeviceState.OFF);
        } finally { actualScheduler.shutdown(); }
    }
}
