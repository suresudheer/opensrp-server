package org.ei.drishti.service;

import org.ei.drishti.contract.*;
import org.ei.drishti.domain.Child;
import org.ei.drishti.domain.Mother;
import org.ei.drishti.dto.BeneficiaryType;
import org.ei.drishti.repository.AllChildren;
import org.ei.drishti.repository.AllMothers;
import org.ei.drishti.service.reporting.ChildReportingService;
import org.ei.drishti.service.reporting.MotherReportingService;
import org.ei.drishti.service.scheduling.ChildSchedulesService;
import org.ei.drishti.util.SafeMap;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;
import org.motechproject.util.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static java.text.MessageFormat.format;
import static org.ei.drishti.common.AllConstants.ChildBirthCommCareFields.BF_POSTBIRTH_COMMCARE_FIELD_NAME;
import static org.ei.drishti.common.AllConstants.ChildBirthCommCareFields.BIRTH_WEIGHT_COMMCARE_FIELD_NAME;
import static org.ei.drishti.common.AllConstants.CommonCommCareFields.CASE_ID_COMMCARE_FIELD_NAME;
import static org.ei.drishti.common.AllConstants.Report.REPORT_EXTRA_DATA_KEY_NAME;
import static org.ei.drishti.dto.AlertPriority.normal;
import static org.ei.drishti.dto.BeneficiaryType.child;
import static org.ei.drishti.dto.BeneficiaryType.mother;

@Service
public class PNCService {
    private static Logger logger = LoggerFactory.getLogger(PNCService.class.toString());

    private ActionService actionService;
    private ChildSchedulesService childSchedulesService;
    private AllMothers allMothers;
    private AllChildren allChildren;
    private MotherReportingService motherReportingService;
    private ChildReportingService childReportingService;

    @Autowired
    public PNCService(ActionService actionService, ChildSchedulesService childSchedulesService, AllMothers allMothers,
                      AllChildren allChildren, MotherReportingService motherReportingService, ChildReportingService childReportingService) {
        this.actionService = actionService;
        this.childSchedulesService = childSchedulesService;
        this.allMothers = allMothers;
        this.allChildren = allChildren;
        this.motherReportingService = motherReportingService;
        this.childReportingService = childReportingService;
    }

    public void registerChild(ChildInformation information) {
        Mother mother = allMothers.findByCaseId(information.motherCaseId());

        if (mother == null) {
            logger.warn(format("Failed to register child as there is no mother registered with case ID: {0} for child case ID: {1} for ANM: {2}", information.motherCaseId(), information.caseId(), information.anmIdentifier()));
            return;
        }

        allChildren.register(new Child(information.caseId(), mother.ecCaseId(), information.motherCaseId(), mother.thaayiCardNo(), information.name(),
                information.immunizationsProvidedList(), information.gender())
                .withAnm(information.anmIdentifier())
                .withDateOfBirth(information.dateOfBirth().toString())
                .withLocation(mother.village(), mother.subCenter(), mother.phc())
                .withDetails(information.details()));

        actionService.registerChildBirth(information.caseId(), information.anmIdentifier(), mother.caseId(), mother.thaayiCardNo(), information.dateOfBirth(), information.gender(), information.details());

        SafeMap reportingData = new SafeMap();
        reportingData.put(CASE_ID_COMMCARE_FIELD_NAME, information.caseId());
        reportingData.put(BIRTH_WEIGHT_COMMCARE_FIELD_NAME, information.childWeight());
        reportingData.put(BF_POSTBIRTH_COMMCARE_FIELD_NAME, information.bfPostBirth());
        childReportingService.registerChild(reportingData);

        alertForMissingImmunization(information, "opv_0", "OPV 0");
        alertForMissingImmunization(information, "bcg", "BCG");
        alertForMissingImmunization(information, "hepb_0", "HEP B0");

        childSchedulesService.enrollChild(information);
    }

    public void pncVisitHappened(PostNatalCareInformation info, Map<String, Map<String, String>> extraData) {
        if (!allMothers.motherExists(info.caseId())) {
            logger.warn("Found PNC visit without registered mother for case ID: " + info.caseId());
            return;
        }

        Map<String, String> details = extraData.get("details");

        Mother updatedMother = allMothers.updateDetails(info.caseId(), details);
        actionService.pncVisitHappened(mother, info.caseId(), info.anmIdentifier(), info.visitDate(), info.visitNumber(), info.numberOfIFATabletsProvided(), updatedMother.details());
        motherReportingService.pncVisitHappened(new SafeMap(extraData.get("reporting")));

        Child child = allChildren.findByMotherCaseId(info.caseId());
        if (child != null) {
            Child updatedChild = allChildren.update(child.caseId(), details);
            actionService.pncVisitHappened(BeneficiaryType.child, child.caseId(), info.anmIdentifier(), info.visitDate(), info.visitNumber(), info.numberOfIFATabletsProvided(), updatedChild.details());
        }
    }

    public void updateChildImmunization(ChildImmunizationUpdationRequest updationRequest, Map<String, Map<String, String>> extraData) {
        if (!allChildren.childExists(updationRequest.caseId())) {
            logger.warn("Found immunization update without registered child for case ID: " + updationRequest.caseId());
            return;
        }

        List<String> previousImmunizations = allChildren.findByCaseId(updationRequest.caseId()).immunizationsProvided();

        Child updatedChild = allChildren.update(updationRequest.caseId(), extraData.get("details"));
        actionService.updateImmunizations(updationRequest.caseId(), updationRequest.anmIdentifier(), updatedChild.details(), updationRequest.immunizationsProvided(),
                updationRequest.immunizationsProvidedDate(), updationRequest.vitaminADose());

        childReportingService.immunizationProvided(new SafeMap(extraData.get(REPORT_EXTRA_DATA_KEY_NAME)), previousImmunizations);

        childSchedulesService.updateEnrollments(updationRequest);
        closeAlertsForProvidedImmunizations(updationRequest);
    }

    public void closeChildCase(ChildCloseRequest childCloseRequest, Map<String, Map<String, String>> extraData) {
        if (!allChildren.childExists(childCloseRequest.caseId())) {
            logger.warn("Found close child request without registered child for case ID: " + childCloseRequest.caseId());
            return;
        }

        allChildren.close(childCloseRequest.caseId());
        actionService.deleteAllAlertsForChild(childCloseRequest.caseId(), childCloseRequest.anmIdentifier());
        actionService.closeChild(childCloseRequest.caseId(), childCloseRequest.anmIdentifier());
        childReportingService.closeChild(new SafeMap(extraData.get(REPORT_EXTRA_DATA_KEY_NAME)));
        childSchedulesService.unenrollChild(childCloseRequest.caseId());
    }

    public void closePNCCase(PostNatalCareCloseInformation closeInformation, Map<String, Map<String, String>> extraData) {
        if (!allMothers.motherExists(closeInformation.caseId())) {
            logger.warn("Found PNC Close visit without registered mother for it: " + closeInformation.caseId());
            return;
        }

        allMothers.close(closeInformation.caseId());
        motherReportingService.closePNC(new SafeMap(extraData.get(REPORT_EXTRA_DATA_KEY_NAME)));
        actionService.closeMother(closeInformation.caseId(), closeInformation.anmIdentifier(), closeInformation.closeReason());
    }

    private void closeAlertsForProvidedImmunizations(ChildImmunizationUpdationRequest updationRequest) {
        for (String immunization : updationRequest.immunizationsProvidedList()) {
            actionService.markAlertAsClosed(updationRequest.caseId(), updationRequest.anmIdentifier(), immunization, updationRequest.immunizationsProvidedDate().toString());
        }
    }

    private void alertForMissingImmunization(ChildInformation information, String checkForThisImmunization, String visitCodeIfNotProvided) {
        if (information.isImmunizationProvided(checkForThisImmunization)) {
            return;
        }

        LocalDate dueDateLocal = information.dateOfBirth().plusDays(2);
        LocalTime currentTime = DateUtil.now().toLocalTime();
        DateTime dueDate = dueDateLocal.toDateTime(currentTime);
        actionService.alertForBeneficiary(child, information.caseId(), visitCodeIfNotProvided, normal, dueDate, dueDateLocal.plusWeeks(1).toDateTime(currentTime));
    }
}
