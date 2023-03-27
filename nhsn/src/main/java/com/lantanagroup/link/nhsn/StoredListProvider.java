package com.lantanagroup.link.nhsn;

import com.lantanagroup.link.IPatientIdProvider;
import com.lantanagroup.link.config.api.ApiConfig;
import com.lantanagroup.link.db.MongoService;
import com.lantanagroup.link.db.model.PatientList;
import com.lantanagroup.link.model.PatientOfInterestModel;
import com.lantanagroup.link.model.ReportContext;
import com.lantanagroup.link.model.ReportCriteria;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Component
public class StoredListProvider implements IPatientIdProvider {
  @Autowired
  private MongoService mongoService;

  private static final Logger logger = LoggerFactory.getLogger(StoredListProvider.class);

  @Override
  public List<PatientOfInterestModel> getPatientsOfInterest(ReportCriteria criteria, ReportContext context, ApiConfig config) {
    context.getPatientLists().clear();
    context.getPatientsOfInterest().clear();

    for (ReportContext.MeasureContext measureContext : context.getMeasureContexts()) {
      Identifier measureIdentifier = measureContext.getMeasure().getIdentifier().get(0);
      String measureId = measureIdentifier.getValue();

      PatientList found = this.mongoService.findPatientList(criteria.getPeriodStart(), criteria.getPeriodEnd(), measureId);

      if (found == null) {
        logger.warn("No patient census lists found");
        continue;
      }

      List<PatientOfInterestModel> patientsOfInterest = found.getPatients().stream().map((patientListId) -> {
        PatientOfInterestModel poi = new PatientOfInterestModel();
        if (StringUtils.isNotEmpty(patientListId.getReference())) {
          poi.setReference(patientListId.getReference());
        } else if (StringUtils.isNotEmpty(patientListId.getIdentifier())) {
          poi.setIdentifier(patientListId.getIdentifier());
        }
        return poi;
      }).collect(Collectors.toList());

      context.getPatientLists().add(found);
      context.getPatientsOfInterest().addAll(patientsOfInterest);
    }

    // Deduplicate POIs, ensuring that ReportContext and MeasureContext POI lists refer to the same objects
    Collector<PatientOfInterestModel, ?, Map<String, PatientOfInterestModel>> deduplicator =
            Collectors.toMap(PatientOfInterestModel::toString, Function.identity(), (poi1, poi2) -> poi1);
    Map<String, PatientOfInterestModel> poiMap = context.getPatientsOfInterest().stream().collect(deduplicator);
    context.setPatientsOfInterest(new ArrayList<>(poiMap.values()));
    for (ReportContext.MeasureContext measureContext : context.getMeasureContexts()) {
      measureContext.setPatientsOfInterest(measureContext.getPatientsOfInterest().stream()
              .collect(deduplicator)
              .values().stream()
              .map(poi -> poiMap.get(poi.toString()))
              .collect(Collectors.toList()));
    }

    logger.info("Loaded {} patients from {} census lists", context.getPatientsOfInterest().size(), context.getPatientLists().size());
    return context.getPatientsOfInterest();
  }
}
