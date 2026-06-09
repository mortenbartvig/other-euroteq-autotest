package dk.dtu.ait.euroteq.autotest.service;

import dk.dtu.ait.euroteq.autotest.dto.SimulationConfigDto;
import dk.dtu.ait.euroteq.autotest.dto.SimulationInstitutionDto;
import dk.dtu.ait.euroteq.autotest.entity.*;
import dk.dtu.ait.euroteq.autotest.repository.*;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SimulatedRunService {

    private final SimulationConfigRepository configRepository;
    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final AppUserRepository appUserRepository;
    private final TestUserRepository testUserRepository;
    private final OfferingRepository offeringRepository;
    private final HomeServerRepository homeServerRepository;
    private final HostServerRepository hostServerRepository;
    private final EntityManager entityManager;
    private final Executor testExecutor;

    public SimulatedRunService(SimulationConfigRepository configRepository,
            TestRunRepository testRunRepository,
            TestResultRepository testResultRepository,
            AppUserRepository appUserRepository,
            TestUserRepository testUserRepository,
            OfferingRepository offeringRepository,
            HomeServerRepository homeServerRepository,
            HostServerRepository hostServerRepository,
            EntityManager entityManager,
            @Qualifier("testExecutor") Executor testExecutor) {
        this.configRepository = configRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.appUserRepository = appUserRepository;
        this.testUserRepository = testUserRepository;
        this.offeringRepository = offeringRepository;
        this.homeServerRepository = homeServerRepository;
        this.hostServerRepository = hostServerRepository;
        this.entityManager = entityManager;
        this.testExecutor = testExecutor;
    }

    public static final List<String> DEFAULT_INSTITUTIONS = List.of(
            "CTU", "DTU", "L'X", "TalTech", "Technion", "TU/e", "TUM"
    );

    public SimulationConfigDto getDefaultConfig() {
        SimulationConfig config = new SimulationConfig();
        config.setGlobalUsersPerInst(5);
        config.setGlobalOfferingsPerInst(3);
      config.setGlobalPassRate(80);
        config.setSlowAsPercent(true);
        config.setSlowPercent(10.0);
        config.setSlowCount(5);
        config.setNormalDurationMin(1.0);
        config.setNormalDurationMax(2.0);
        config.setSlowDurationMin(15.0);
        config.setSlowDurationMax(25.0);

        List<SimulationInstitution> institutions = DEFAULT_INSTITUTIONS.stream()
                .map(name -> {
                    SimulationInstitution inst = new SimulationInstitution();
                    inst.setName(name);
                    return inst;
                })
                .collect(Collectors.toList());

        config.setInstitutions(institutions);
        return SimulationConfigDto.from(config);
    }

    public SimulationConfigDto getConfig(Long userId) {
        return configRepository.findByUserId(userId)
                .map(SimulationConfigDto::from)
                .orElseGet(this::getDefaultConfig);
    }

    public SimulationConfigDto saveConfig(Long userId, SimulationConfigDto dto) {
        SimulationConfig config = configRepository.findByUserId(userId)
                .orElse(new SimulationConfig());

        config.setUserId(userId);
        config.setGlobalUsersPerInst(dto.getGlobalUsersPerInst());
        config.setGlobalOfferingsPerInst(dto.getGlobalOfferingsPerInst());
  config.setGlobalPassRate(dto.getGlobalPassRate());
        config.setSlowAsPercent(dto.getSlowAsPercent());
        config.setSlowPercent(dto.getSlowPercent());
        config.setSlowCount(dto.getSlowCount());
        config.setNormalDurationMin(dto.getNormalDurationMin());
        config.setNormalDurationMax(dto.getNormalDurationMax());
        config.setSlowDurationMin(dto.getSlowDurationMin());
        config.setSlowDurationMax(dto.getSlowDurationMax());

        if (dto.getInstitutions() != null) {
            if (config.getInstitutions() == null) {
                config.setInstitutions(new java.util.ArrayList<>());
            }
            config.getInstitutions().clear();
 for (SimulationInstitutionDto instDto : dto.getInstitutions()) {
                SimulationInstitution inst = new SimulationInstitution();
                inst.setName(instDto.getName());
                inst.setUsersOverride(instDto.getUsersOverride());
                inst.setOfferingsOverride(instDto.getOfferingsOverride());
                boolean useGlobal = instDto.getUseGlobalPassRate() != null ? instDto.getUseGlobalPassRate() : true;
                inst.setUseGlobalPassRate(useGlobal);
                inst.setPassRateOverride(useGlobal ? null : instDto.getPassRateOverride());
                inst.setHomeServerOffline(instDto.isHomeServerOffline());
                inst.setHostServerOffline(instDto.isHostServerOffline());
                inst.setConfig(config);
                config.getInstitutions().add(inst);
            }
        }

        config = configRepository.save(config);
        return SimulationConfigDto.from(config);
    }

    public List<SimulationInstitution> getDefaultInstitutions() {
        return DEFAULT_INSTITUTIONS.stream()
                .map(name -> {
                    SimulationInstitution inst = new SimulationInstitution();
                    inst.setName(name);
                    return inst;
                })
                .collect(Collectors.toList());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateTestRunStatus(TestRun testRun) {
        testRunRepository.save(testRun);
    }

   @Transactional
    public Long runSimulation(Long userId) {
        AppUser user = appUserRepository.findById(userId).orElseThrow();
        SimulationConfig config = configRepository.findByUserIdWithInstitutions(userId).orElseGet(() -> {
            SimulationConfig dummy = new SimulationConfig();
            dummy.setUserId(userId);
            dummy.setGlobalUsersPerInst(5);
            dummy.setGlobalOfferingsPerInst(3);
   dummy.setGlobalPassRate(80);
            dummy.setSlowAsPercent(true);
            dummy.setSlowPercent(10.0);
            dummy.setSlowCount(5);
            dummy.setNormalDurationMin(1.0);
            dummy.setNormalDurationMax(2.0);
            dummy.setSlowDurationMin(15.0);
            dummy.setSlowDurationMax(25.0);
            List<SimulationInstitution> institutions = DEFAULT_INSTITUTIONS.stream()
                    .map(name -> {
                        SimulationInstitution inst = new SimulationInstitution();
                        inst.setName(name);
                        return inst;
                    })
                    .collect(Collectors.toList());
            dummy.setInstitutions(institutions);
            return dummy;
        });

        Set<String> offlineHomeInstitutions = new HashSet<>();
        Set<String> offlineHostInstitutions = new HashSet<>();
        for (SimulationInstitution inst : config.getInstitutions()) {
            if (inst.isHomeServerOffline()) offlineHomeInstitutions.add(inst.getName());
            if (inst.isHostServerOffline()) offlineHostInstitutions.add(inst.getName());
        }

        TestRun testRun = new TestRun();
        testRun.setStartedAt(Instant.now());
        testRun.setStartedBy(user);
        testRun.setStatus(TestRun.Status.PENDING);
        testRun.setSimulated(true);
        testRun.setOfflineHomeInstitutions(offlineHomeInstitutions);
        testRun.setOfflineHostInstitutions(offlineHostInstitutions);
        testRun = testRunRepository.save(testRun);

        // Assign negative IDs to institutions for the matrix grid
        Map<String, Long> instToHomeId = new LinkedHashMap<>();
        Map<String, Long> instToHostId = new LinkedHashMap<>();
        long id = -1;
        for (SimulationInstitution inst : config.getInstitutions()) {
            String instName = inst.getName();
            instToHomeId.put(instName, id);
            instToHostId.put(instName, id);
            id--;
        }
        testRun.setInstitutionServerMapping(new HashMap<>(instToHomeId));
        testRunRepository.save(testRun);
        entityManager.flush();

        log.info("Starting simulated test run {} for user {}, {} institutions", testRun.getId(), userId, config.getInstitutions().size());

AtomicInteger completedTests = new AtomicInteger(0);
        AtomicInteger testCounter = new AtomicInteger(0);
        List<Runnable> tasks = new ArrayList<>();

        // Generate results for all institution-pair combinations
        List<SimulationInstitution> institutions = new ArrayList<>(config.getInstitutions());
        for (SimulationInstitution homeInst : institutions) {
            for (SimulationInstitution hostInst : institutions) {
                boolean homeOffline = homeInst.isHomeServerOffline();
                boolean hostOffline = hostInst.isHostServerOffline();
                if (homeOffline || hostOffline) continue;

                int users = homeInst.getUsersOverride() != null ? homeInst.getUsersOverride() : config.getGlobalUsersPerInst();
                int offerings = hostInst.getOfferingsOverride() != null ? hostInst.getOfferingsOverride() : config.getGlobalOfferingsPerInst();

                for (int u = 1; u <= Math.min(users, 3); u++) {
                    for (int o = 1; o <= Math.min(offerings, 3); o++) {
                        totalTests.incrementAndGet();
                        final SimulationInstitution finalHomeInst = homeInst;
                        final SimulationInstitution finalHostInst = hostInst;
                        final TestRun finalTestRun = testRun;
                        final int finalU = u;
                        final int finalO = o;

                        boolean isSlow;
                        if (config.getSlowAsPercent()) {
                            isSlow = (Math.random() * 100) < config.getSlowPercent();
                        } else {
                            isSlow = testCounter.getAndIncrement() < config.getSlowCount();
                        }
                        final boolean finalIsSlow = isSlow;

                        tasks.add(() -> {
                            try {
                                double normalMin = config.getNormalDurationMin();
                                double normalMax = config.getNormalDurationMax();
                                double slowMin = config.getSlowDurationMin();
                                double slowMax = config.getSlowDurationMax();

                                double delaySec = finalIsSlow ?
                                        slowMin + Math.random() * (slowMax - slowMin) :
                                        normalMin + Math.random() * (normalMax - normalMin);

                                Instant startedAt = Instant.now();
                                Thread.sleep((long) (delaySec * 1000));
                                Instant completedAt = Instant.now();

                                createMockResultInTransaction(finalTestRun, finalHomeInst, finalHostInst, finalU, finalO, finalIsSlow, startedAt, completedAt,
                                        config.getNormalDurationMin(), config.getNormalDurationMax(), config.getSlowDurationMin(), config.getSlowDurationMax());
                                completedTests.incrementAndGet();
                                finalTestRun.setStatus(TestRun.Status.RUNNING);
                                updateTestRunStatus(finalTestRun);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                log.error("Error in simulated test for {} home, {} host, user {}, offering {}",
                                        finalHomeInst.getName(), finalHostInst.getName(), finalU, finalO, e);
                            }
                        });
                    }
                }
            }
        }

        final TestRun finalTestRunForComplete = testRun;
        // Submit tasks only after the TestRun transaction commits so the FK constraint is satisfied
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    List<CompletableFuture<Void>> futures = tasks.stream()
                            .map(task -> CompletableFuture.runAsync(task, testExecutor))
                            .collect(Collectors.toList());
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                        finalTestRunForComplete.setCompletedAt(Instant.now());
                        finalTestRunForComplete.setStatus(TestRun.Status.COMPLETED);
                        finalTestRunForComplete.setStatusMessage("Simulated run completed: " + completedTests.get() + " tests");
                        updateTestRunStatus(finalTestRunForComplete);
                        log.info("Simulated test run {} completed: {} tests", finalTestRunForComplete.getId(), completedTests.get());
                    });
                } catch (Exception e) {
                    log.error("Failed to submit simulation tasks for test run {}", finalTestRunForComplete.getId(), e);
                    finalTestRunForComplete.setStatus(TestRun.Status.FAILED);
                    finalTestRunForComplete.setStatusMessage("Failed to submit simulation tasks: " + e.getMessage());
                    updateTestRunStatus(finalTestRunForComplete);
                }
            }
        });
        return testRun.getId();
    }

     public void createMockResultInTransaction(TestRun testRun, SimulationInstitution homeInst, SimulationInstitution hostInst, int userNum, int offeringNum, boolean isSlow, Instant startedAt, Instant completedAt,
            Double normalMin, Double normalMax, Double slowMin, Double slowMax) {
        createMockResult(testRun, homeInst, hostInst, userNum, offeringNum, isSlow, startedAt, completedAt, normalMin, normalMax, slowMin, slowMax);
    }

    private void createMockResult(TestRun testRun, SimulationInstitution homeInst, SimulationInstitution hostInst, int userNum, int offeringNum, boolean isSlow, Instant startedAt, Instant completedAt,
            Double normalMin, Double normalMax, Double slowMin, Double slowMax) {
        TestResult result = new TestResult();

        String userKey = "sim." + homeInst.getName().toLowerCase() + "." + userNum;
        TestUser mockUser = testUserRepository.findByUsername(userKey).orElse(null);
        if (mockUser == null) {
            mockUser = new TestUser();
            mockUser.setName("sim-" + homeInst.getName() + "-user" + userNum);
            mockUser.setUsername(userKey);
            mockUser.setHomeServer(null);
            mockUser = testUserRepository.save(mockUser);
        }

        String offeringKey = "sim-" + hostInst.getName().toLowerCase() + "-offering-" + offeringNum;
        Offering mockOffering = offeringRepository.findByOfferingId(offeringKey).orElse(null);
        if (mockOffering == null) {
            mockOffering = new Offering();
            mockOffering.setName("sim-" + hostInst.getName() + "-off" + offeringNum);
            mockOffering.setOfferingId(offeringKey);
            mockOffering.setHostServer(null);
            mockOffering = offeringRepository.save(mockOffering);
        }

        result.setTestRun(testRun);
        result.setTestUser(mockUser);
        result.setOffering(mockOffering);
        result.setStartedAt(startedAt);

        int passRate = Math.min(getPassRate(homeInst), getPassRate(hostInst));
        boolean shouldFail = Math.random() * 100 < (100 - passRate);

        if (shouldFail) {
            int failStepIdx = new Random().nextInt(6);
            StepFailure failure = generateStepFailure(failStepIdx, homeInst, hostInst);
            result.setActualResult(TestResult.ActualResult.ERROR);
            result.setErrorMessage(failure.errorMessage);

            // Build step details with one failed step
            List<String> phases = List.of("setup", "enrollment", "verification", "cleanup");
            List<String> stepNames = List.of(
                    "get_access_token", "get_person", "broker_enrollment",
                    "verify_association", "verify_result", "cleanup_association"
            );

  StringBuilder stepsJson = new StringBuilder("[");
            Instant stepStart = result.getStartedAt();

            for (int i = 0; i < stepNames.size(); i++) {
                double delay = isSlow ?
                        (slowMin != null ? slowMin : 15.0) + Math.random() * ((slowMax != null ? slowMax : 25.0) - (slowMin != null ? slowMin : 15.0)) / stepNames.size() :
                        (normalMin != null ? normalMin : 1.0) + Math.random() * ((normalMax != null ? normalMax : 2.0) - (normalMin != null ? normalMin : 1.0)) / stepNames.size();
                stepStart = stepStart.plusSeconds((long) delay);

                String phase = phases.get(Math.min(i, phases.size() - 1));
                String homeUrl = "https://" + homeInst.getName().toLowerCase() + ".edu/endpoint/" + (i + 1);
                String hostUrl = "https://" + hostInst.getName().toLowerCase() + ".edu/endpoint/" + (i + 1);
                String actor = i == 0 ? "Mock OAuth" : "Home Server";

                String status;
                String errMsg = null;
                if (i == failStepIdx) {
                    status = "ERROR";
                    errMsg = failure.errorMessage();
                } else {
                    status = "SUCCESS";
                }

                if (i > 0) stepsJson.append(",");
                stepsJson.append(String.format(
                        "{\"step\":\"%s\",\"phase\":\"%s\",\"status\":\"%s\"," +
                        "\"duration\":%.3f,\"url\":\"%s\",\"actor\":\"%s\"," +
                        "\"startedAt\":\"%s\",\"completedAt\":\"%s\"" +
                        "%s}",
                        stepNames.get(i), phase, status, delay, hostUrl, actor,
                        stepStart.toString(),
                        stepStart.plusSeconds(1).toString(),
                        errMsg != null ? ",\"errorMessage\":\"" + escapeJson(errMsg) + "\"" : ""
                ));
            }

stepsJson.append("]");
            result.setStepDetails(stepsJson.toString());
          result.setHasWarnings(false);
        } else {
            result.setActualResult(TestResult.ActualResult.SUCCESS);

            List<String> phases = List.of("setup", "enrollment", "verification", "cleanup");
            List<String> stepNames = List.of(
                    "get_access_token", "get_person", "broker_enrollment",
                    "verify_association", "verify_result", "cleanup_association"
            );

            StringBuilder stepsJson = new StringBuilder("[");
            Instant stepStart = result.getStartedAt();

            for (int i = 0; i < stepNames.size(); i++) {
                double delay = isSlow ?
                        (slowMin != null ? slowMin : 15.0) + Math.random() * ((slowMax != null ? slowMax : 25.0) - (slowMin != null ? slowMin : 15.0)) / stepNames.size() :
                        (normalMin != null ? normalMin : 1.0) + Math.random() * ((normalMax != null ? normalMax : 2.0) - (normalMin != null ? normalMin : 1.0)) / stepNames.size();
                stepStart = stepStart.plusSeconds((long) delay);

                String phase = phases.get(Math.min(i, phases.size() - 1));
                String hostUrl = "https://" + hostInst.getName().toLowerCase() + ".edu/endpoint/" + (i + 1);
                String actor = i == 0 ? "Mock OAuth" : "Home Server";

                if (i > 0) stepsJson.append(",");
                stepsJson.append(String.format(
                        "{\"step\":\"%s\",\"phase\":\"%s\",\"status\":\"%s\"," +
                        "\"duration\":%.3f,\"url\":\"%s\",\"actor\":\"%s\"," +
                        "\"startedAt\":\"%s\",\"completedAt\":\"%s\"" +
                        "%s}",
                        stepNames.get(i), phase, "SUCCESS", delay, hostUrl, actor,
                        stepStart.toString(),
                        stepStart.plusSeconds(1).toString(),
                        ""
                ));
            }

            stepsJson.append("]");
            result.setStepDetails(stepsJson.toString());
            result.setHasWarnings(false);
        }

        result.setCompletedAt(completedAt);
        long durationMs = Duration.between(startedAt, completedAt).toMillis();
        result.setSlow(durationMs >= 5000);
        result.setVerySlow(durationMs >= 20000);
        testResultRepository.save(result);
    }

    private StepFailure generateStepFailure(int stepIdx, SimulationInstitution homeInst, SimulationInstitution hostInst) {
        String host = hostInst.getName().toLowerCase();
        String home = homeInst.getName().toLowerCase();
        switch (stepIdx) {
            case 0 -> {
                String[] msgs = {
                    "Failed to obtain access token from " + host + ": invalid_client",
                    "Token endpoint returned HTTP 401 on " + host + ": client authentication failed"
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "client_authentication_failed");
            }
            case 1 -> {
                String[] msgs = {
                    "Person endpoint on " + home + " returned HTTP 403: access denied for this client",
                    "Failed to retrieve person data from " + home + ": insufficient privileges"
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "access_denied");
            }
            case 2 -> {
                String[] msgs = {
                    "Broker enrollment on " + home + " failed: enrollment service unavailable",
                    "Failed to establish broker connection with " + home + ": connection refused"
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "enrollment_service_unavailable");
            }
            case 3 -> {
                String[] msgs = {
                    "Association verification failed: association_id not found on " + host,
                    "Failed to verify association on " + host + ": invalid association state"
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "association_not_found");
            }
            case 4 -> {
                String[] msgs = {
                    "Result verification failed: mismatch between home and host results on " + host,
                    "Test result verification error: inconsistent data from " + host
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "result_mismatch");
            }
            case 5 -> {
                String[] msgs = {
                    "Association cleanup failed on " + host + ": cleanup timeout after 30s",
                    "Failed to clean up association on " + host + ": resource locked"
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "cleanup_timeout");
            }
            default -> {
                String[] msgs = {
                    "Step execution failed on " + host + ": unexpected error",
                    "Simulated failure at step " + stepIdx + " on " + host
                };
                return new StepFailure(msgs[new Random().nextInt(msgs.length)], "step_execution_error");
            }
        }
    }

    private int getPassRate(SimulationInstitution inst) {
        if (inst.getPassRateOverride() != null) {
            return inst.getPassRateOverride();
        }
        if (inst.getConfig() != null && inst.getConfig().getGlobalPassRate() != null) {
            return inst.getConfig().getGlobalPassRate();
        }
        return 80;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record StepFailure(String errorMessage, String errorCode) {}

    private static AtomicInteger totalTests = new AtomicInteger(0);
}
