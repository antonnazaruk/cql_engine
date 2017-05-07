package org.opencds.cqf.cql.data.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.dstu3.model.*;
import org.hl7.fhir.exceptions.FHIRException;
import org.joda.time.LocalTime;
import org.joda.time.Partial;
import org.joda.time.ReadablePartial;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.opencds.cqf.cql.data.DataProvider;
import org.opencds.cqf.cql.runtime.Code;
import org.opencds.cqf.cql.runtime.DateTime;
import org.opencds.cqf.cql.runtime.Interval;
import org.opencds.cqf.cql.runtime.Time;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Bryn on 9/13/2016.
 */
public abstract class BaseFhirDataProvider implements DataProvider
{
    protected FhirContext fhirContext;

    public BaseFhirDataProvider() {
        this.packageName = "org.hl7.fhir.dstu3.model";
        this.fhirContext = FhirContext.forDstu3();
    }

    // for DSTU2 and earlier support
    public void setFhirContext(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }
    public FhirContext getFhirContext() { return fhirContext; }

    @Override
    public Iterable<Object> retrieve(String context, Object contextValue, String dataType, String templateId, String codePath, Iterable<Code> codes, String valueSet, String datePath, String dateLowPath, String dateHighPath, Interval dateRange) {
        return null;
    }

    private String packageName;
    @Override
    public String getPackageName() {
        return this.packageName;
    }

    public void setPackageName(String packageName) {
      this.packageName = packageName;
    }

    public BaseFhirDataProvider withPackageName(String packageName) {
      setPackageName(packageName);
      return this;
    }

    protected DateTime toDateTime(Date result) {
        // NOTE: By going through the Java primitive here, we are losing the precision support of the HAPI-DateTimeType
        return DateTime.fromJavaDate(result);
    }

    protected DateTime toDateTime(DateTimeType value) {
        // TODO: Convert tzHour, tzMin and tzSign to a BigDecimal to set TimeZoneOffset
        // NOTE: month is 0-indexed, hence the +1
        switch (value.getPrecision()) {
            case YEAR: return new DateTime(value.getYear());
            case MONTH: return new DateTime(value.getYear(), value.getMonth() + 1);
            case DAY: return new DateTime(value.getYear(), value.getMonth() + 1, value.getDay());
            case SECOND: return new DateTime(value.getYear(), value.getMonth() + 1, value.getDay(), value.getHour(), value.getMinute(), value.getSecond());
            case MILLI: return new DateTime(value.getYear(), value.getMonth() + 1, value.getDay(), value.getHour(), value.getMinute(), value.getSecond(), value.getMillis());
            default: throw new IllegalArgumentException(String.format("Invalid temporal precision %s", value.getPrecision().toString()));
        }
    }

    protected DateTime toDateTime(DateType value) {
        // TODO: This ought to work, but I'm getting an incorrect month value returned from the Hapi DateType, looks like a Java Calendar problem?
        switch (value.getPrecision()) {
            case YEAR: return new DateTime(value.getYear());
            case MONTH: return new DateTime(value.getYear(), value.getMonth() + 1); // Month is zero based in DateType.
            case DAY: return new DateTime(value.getYear(), value.getMonth() + 1, value.getDay());
            default: throw new IllegalArgumentException(String.format("Invalid temporal precision %s", value.getPrecision().toString()));
        }
    }

    protected Time toTime(TimeType value) {
        DateTimeFormatter dtf = ISODateTimeFormat.timeParser();
        ReadablePartial partial = new LocalTime(dtf.parseMillis(value.getValue()));
        return new Time().withPartial(new Partial(partial));
    }

    protected DateTime toDateTime(InstantType value) {
        // TODO: Timezone support
        return new DateTime(value.getYear(), value.getMonth(), value.getDay(), value.getHour(), value.getMinute(), value.getSecond(), value.getMillis());
    }

    protected Object fromJavaPrimitive(Object value, Object target) {
        if (target instanceof DateTimeType) {
            return new Date(); // TODO: Why is this so hard?
        }
        else if (target instanceof DateType) {
            return new Date();
        }
        else if (target instanceof TimeType) {
            if (value instanceof Time) {
                return ((Time) value).getPartial().toString();
            }
            return new Date();
        }
        else {
            return value;
        }
    }

    protected Object toJavaPrimitive(Object result, Object source) {
        if (source instanceof DateTimeType) {
            return toDateTime((DateTimeType)source);
        }
        else if (source instanceof DateType) {
            return toDateTime((DateType)source);
        }
        else if (source instanceof TimeType) {
            return toTime((TimeType)source);
        }
        else if (source instanceof InstantType) {
            return toDateTime((InstantType)source);
        }
        else {
            return result;
        }

        // The HAPI primitive types use the same Java types as the CQL Engine with the exception of the date types,
        // where the HAPI classes return Java Dates, the engine expects runtime.DateTime instances

//        if (result instanceof BoundCodeDt) {
//            return ((BoundCodeDt)result).getValue();
//        }
//
//        if (result instanceof BaseDateTimeDt) {
//            return DateTime.fromJavaDate(((BaseDateTimeDt)result).getValue());
//        }
//
//        if (result instanceof BaseDateTimeType) {
//            return DateTime.fromJavaDate(((BaseDateTimeType)result).getValue());
//        }
//
//        if (result instanceof TimeType) {
//            return toTime((TimeType)result);
//        }
//
//        return result;
    }

    protected boolean pathIsChoice(String path) {
        // Pretty consistent format: lowercase root followed by Type.
        // outliers
        if (path.startsWith("notDoneReason") || path.endsWith("valueSet")
                || path.endsWith("multipleBirth") || path.endsWith("asNeeded")
                || path.endsWith("onBehalfOf") || path.endsWith("defaultValue")) {
            return true;
        }

        // get the substring from first uppercase to end of string
        Pattern pattern = Pattern.compile("[A-Z].*");
        Matcher matcher = pattern.matcher(path);
        String type = path;
        if (matcher.find()) {
            type = matcher.group();
        }

        try {
            Class.forName(String.format("%s.%s", getPackageName(), type));
        } catch (ClassNotFoundException e) {
            return false;
        }

        return true;
    }

    protected Object resolveChoiceProperty(Object target, String path, String typeName) {
        String rootPath = path.substring(0, path.indexOf(typeName));
        return resolveProperty(target, rootPath);
    }

    protected Object resolveChoiceProperty(Object target, String path) {
        Pattern pattern = Pattern.compile("[A-Z].*");
        Matcher matcher = pattern.matcher(path);
        String type = path;
        if (matcher.find()) {
            type = matcher.group();
        }

        Class clazz;
        try {
            clazz = Class.forName(String.format("%s.%s", getPackageName(), type));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Error resolving choice property: " + e.getMessage());
        }

        Object object = resolveChoiceProperty(target, path, type);
        return clazz.isInstance(object) ? object : null;
    }

    protected Object resolveProperty(Object target, String path) {
        if (target == null) {
            return null;
        }

        if (target instanceof Enumeration && path.equals("value")) {
            return ((Enumeration)target).getValueAsString();
        }

        else if (target.getClass().getSimpleName().contains("EnumFactory") && path.equals("value")) {
            return target.toString();
        }

        // TODO: find a better way for choice types ...
        else if (path.equals("asNeededBoolean") || path.equals("asNeededCodeableConcept")) {
            path = "asNeeded";
        }

        Class<? extends Object> clazz = target.getClass();
        try {
            String accessorMethodName = String.format("%s%s%s", "get", path.substring(0, 1).toUpperCase(), path.substring(1));
            String elementAccessorMethodName = String.format("%sElement", accessorMethodName);
            Method accessor = null;
            try {
                accessor = clazz.getMethod(elementAccessorMethodName);
            }
            catch (NoSuchMethodException e) {
                accessor = clazz.getMethod(accessorMethodName);
            }

            Object result = accessor.invoke(target);
            result = toJavaPrimitive(result, target);
            return result;
        } catch (NoSuchMethodException e) {
            if (pathIsChoice(path)) {
                return resolveChoiceProperty(target, path);
            }
            else {
                throw new IllegalArgumentException(String.format("Could not determine accessor function for property %s of type %s", path, clazz.getSimpleName()));
            }
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(String.format("Errors occurred attempting to invoke the accessor function for property %s of type %s", path, clazz.getSimpleName()));
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(String.format("Could not invoke the accessor function for property %s of type %s", path, clazz.getSimpleName()));
        }
    }

    @Override
    public Object resolvePath(Object target, String path) {
        String[] identifiers = path.split("\\.");
        for (String identifier : identifiers) {
            // handling indexes: item[0].code
            if (identifier.contains("[")) {
                int j = Character.getNumericValue(identifier.charAt(identifier.indexOf("[") + 1));
                target = resolveProperty(target, identifier.replaceAll("\\[\\d\\]", ""));
                target = ((ArrayList) target).get(j);
            } else
                target = resolveProperty(target, identifier);
        }

        return target;
    }

    @Override
    public Class resolveType(String typeName) {
        try {
            // TODO: Obviously would like to be able to automate this, but there is no programmatic way of which I'm aware
            // For the primitive types, not such a big deal.
            // For the enumerations, the type names are generated from the binding name in the spreadsheet, which doesn't make it to the StructureDefinition,
            // and the schema has no way of indicating whether the enum will be common (i.e. in Enumerations) or per resource
            switch (typeName) {
                case "base64Binary": typeName = "Base64BinaryType"; break;
                case "boolean": typeName = "BooleanType"; break;
                case "dateTime": typeName = "DateTimeType"; break;
                case "date": typeName = "DateType"; break;
                case "decimal": typeName = "DecimalType"; break;
                case "instant": typeName = "InstantType"; break;
                case "integer": typeName = "IntegerType"; break;
                case "positiveInt": typeName = "PositiveIntType"; break;
                case "unsignedInt": typeName = "UnsignedIntType"; break;
                case "string": typeName = "StringType"; break;
                case "code": typeName = "CodeType"; break;
                case "markdown": typeName = "MarkdownType"; break;
                case "time": typeName = "TimeType"; break;
                case "uri": typeName = "UriType"; break;
                case "uuid": typeName = "UuidType"; break;
                case "id": typeName = "IdType"; break;
                case "oid": typeName = "OidType"; break;
                case "TestScriptRequestMethodCode": typeName = "TestScript$TestScriptRequestMethodCode"; break;
                case "ActionPrecheckBehavior": typeName = "PlanDefinition$ActionPrecheckBehavior"; break;
                case "ProvenanceEntityRole": typeName = "Provenance$ProvenanceEntityRole"; break;
                case "UnitsOfTime": typeName = "Timing$UnitsOfTime"; break;
                case "AddressType": typeName = "Address$AddressType"; break;
                case "AllergyIntoleranceCategory": typeName = "AllergyIntolerance$AllergyIntoleranceCategory"; break;
                case "SpecimenStatus": typeName = "Specimen$SpecimenStatus"; break;
                case "RestfulCapabilityMode": typeName = "CapabilityStatement$RestfulCapabilityMode"; break;
                case "DetectedIssueSeverity": typeName = "DetectedIssue$DetectedIssueSeverity"; break;
                case "IssueSeverity": typeName = "OperationOutcome$IssueSeverity"; break;
                case "CareTeamStatus": typeName = "CareTeam$CareTeamStatus"; break;
                case "DataElementStringency": typeName = "DataElement$DataElementStringency"; break;
                case "VisionEyes": typeName = "VisionPrescription$VisionEyes"; break;
                case "VisionBase": typeName = "VisionPrescription$VisionBase"; break;
                case "StructureMapSourceListMode": typeName = "StructureMap$StructureMapSourceListMode"; break;
                case "RequestStatus": typeName="RequestGroup$RequestStatus"; break;
                case "RequestIntent": typeName="RequestGroup$RequestIntent"; break;
                case "RequestPriority": typeName="RequestGroup$RequestPriority"; break;
                case "ActionConditionKind": typeName = "PlanDefinition$ActionConditionKind"; break;
                case "EncounterStatus": typeName = "Encounter$EncounterStatus"; break;
                case "ChargeItemStatus": typeName = "ChargeItem$ChargeItemStatus"; break;
                case "ActionParticipantType": typeName = "PlanDefinition$ActionParticipantType"; break;
                case "StructureDefinitionKind": typeName = "StructureDefinition$StructureDefinitionKind"; break;
                case "PublicationStatus": typeName = "Enumerations$PublicationStatus"; break;
                case "TestReportResult": typeName = "TestReport$TestReportResult"; break;
                case "ConceptMapGroupUnmappedMode": typeName = "ConceptMap$ConceptMapGroupUnmappedMode"; break;
                case "ConsentDataMeaning": typeName = "Consent$ConsentDataMeaning"; break;
                case "QuestionnaireResponseStatus": typeName = "QuestionnaireResponse$QuestionnaireResponseStatus"; break;
                case "SearchComparator": typeName = "SearchParameter$SearchComparator"; break;
                case "AllergyIntoleranceType": typeName = "AllergyIntolerance$AllergyIntoleranceType"; break;
                case "DocumentRelationshipType": typeName = "DocumentReference$DocumentRelationshipType"; break;
                case "AllergyIntoleranceClinicalStatus": typeName = "AllergyIntolerance$AllergyIntoleranceClinicalStatus"; break;
                case "CarePlanActivityStatus": typeName = "CarePlan$CarePlanActivityStatus"; break;
                case "ActionList": typeName = "ProcessRequest$ActionList"; break;
                case "ParticipationStatus": typeName = "Appointment$ParticipationStatus"; break;
                case "ActionSelectionBehavior": typeName = "PlanDefinition$ActionSelectionBehavior"; break;
                case "DocumentMode": typeName = "CapabilityStatement$DocumentMode"; break;
                case "AssertionOperatorType": typeName = "TestScript$AssertionOperatorType"; break;
                case "DaysOfWeek": typeName = "HealthcareService$DaysOfWeek"; break;
                case "IssueType": typeName = "OperationOutcome$IssueType"; break;
                case "ContentType": typeName = "TestScript$ContentType"; break;
                case "StructureMapContextType": typeName = "StructureMap$StructureMapContextType"; break;
                case "FamilyHistoryStatus": typeName = "FamilyMemberHistory$FamilyHistoryStatus"; break;
                case "MedicationStatementCategory": typeName = "MedicationStatement$MedicationStatementCategory"; break;
                case "CommunicationStatus": typeName = "Communication$CommunicationStatus"; break;
                case "ClinicalImpressionStatus": typeName = "ClinicalImpression$ClinicalImpressionStatus"; break;
                case "AssertionResponseTypes": typeName = "TestScript$AssertionResponseTypes"; break;
                case "NarrativeStatus": typeName = "Narrative$NarrativeStatus"; break;
                case "ReferralCategory": typeName = "ReferralRequest$ReferralCategory"; break;
                case "MeasmntPrinciple": typeName = "DeviceComponent$MeasmntPrinciple"; break;
                case "ConsentExceptType": typeName = "Consent$ConsentExceptType"; break;
                case "EndpointStatus": typeName = "Endpoint$EndpointStatus"; break;
                case "GuidePageKind": typeName = "ImplementationGuide$GuidePageKind"; break;
                case "GuideDependencyType": typeName = "ImplementationGuide$GuideDependencyType"; break;
                case "ResourceVersionPolicy": typeName = "CapabilityStatement$ResourceVersionPolicy"; break;
                case "MedicationRequestStatus": typeName = "MedicationRequest$MedicationRequestStatus"; break;
                case "MedicationRequestIntent": typeName = "MedicationRequest$MedicationRequestIntent"; break;
                case "MedicationRequestPriority": typeName = "MedicationRequest$MedicationRequestPriority"; break;
                case "MedicationAdministrationStatus": typeName = "MedicationAdministration$MedicationAdministrationStatus"; break;
                case "NamingSystemIdentifierType": typeName = "NamingSystem$NamingSystemIdentifierType"; break;
                case "AccountStatus": typeName = "Account$AccountStatus"; break;
                case "ProcedureRequestPriority": typeName = "ProcedureRequest$ProcedureRequestPriority"; break;
                case "MedicationDispenseStatus": typeName = "MedicationDispense$MedicationDispenseStatus"; break;
                case "IdentifierUse": typeName = "Identifier$IdentifierUse"; break;
                case "DigitalMediaType": typeName = "Media$DigitalMediaType"; break;
                case "TestReportParticipantType": typeName = "TestReport$TestReportParticipantType"; break;
                case "BindingStrength": typeName = "Enumerations$BindingStrength"; break;
                case "ConsentState": typeName = "Consent$ConsentState"; break;
                case "ParticipantRequired": typeName = "Appointment$ParticipantRequired"; break;
                case "DiscriminatorType": typeName = "ElementDefinition$DiscriminatorType"; break;
                case "XPathUsageType": typeName = "SearchParameter$XPathUsageType"; break;
                case "StructureMapInputMode": typeName = "StructureMap$StructureMapInputMode"; break;
                case "InstanceAvailability": typeName = "ImagingStudy$InstanceAvailability"; break;
                case "ImmunizationStatusCodes": typeName = "Immunization$ImmunizationStatus"; break;
                case "ConfidentialityClassification": typeName = "Composition$DocumentConfidentiality"; break;
                case "LinkageType": typeName = "Linkage$LinkageType"; break;
                case "ReferenceHandlingPolicy": typeName = "CapabilityStatement$ReferenceHandlingPolicy"; break;
                case "FilterOperator": typeName = "CodeSystem$FilterOperator"; break;
                case "NamingSystemType": typeName = "NamingSystem$NamingSystemType"; break;
                case "ResearchStudyStatus": typeName = "ResearchStudy$ResearchStudyStatus"; break;
                case "ExtensionContext": typeName = "StructureDefinition$ExtensionContext"; break;
                case "AuditEventOutcome": typeName = "AuditEvent$AuditEventOutcome"; break;
                case "ConstraintSeverity": typeName = "ElementDefinition$ConstraintSeverity"; break;
                case "EventCapabilityMode": typeName = "CapabilityStatement$EventCapabilityMode"; break;
                case "ProcedureStatus": typeName = "Procedure$ProcedureStatus"; break;
                case "ResearchSubjectStatus": typeName = "ResearchSubject$ResearchSubjectStatus"; break;
                case "ActionGroupingBehavior": typeName = "PlanDefinition$ActionGroupingBehavior"; break;
                case "CompositeMeasureScoring": typeName = "Measure$CompositeMeasureScoring"; break;
                case "DeviceMetricCategory": typeName = "DeviceMetric$DeviceMetricCategory"; break;
                case "QuestionnaireStatus": typeName = "Questionnaire$QuestionnaireStatus"; break;
                case "StructureMapTransform": typeName = "StructureMap$StructureMapTransform"; break;
                case "StructureMapTargetListMode": typeName = "StructureMap$StructureMapTargetListMode"; break;
                case "ResponseType": typeName = "MessageHeader$ResponseType"; break;
                case "AggregationMode": typeName = "ElementDefinition$AggregationMode"; break;
                case "CapabilityStatementKind": typeName = "CapabilityStatement$CapabilityStatementKind"; break;
                case "sequenceType": typeName = "Sequence$SequenceType"; break;
                case "AllergyIntoleranceVerificationStatus": typeName = "AllergyIntolerance$AllergyIntoleranceVerificationStatus"; break;
                case "EventTiming": typeName = "Timing$EventTiming"; break;
                case "GoalStatus": typeName = "Goal$GoalStatus"; break;
                case "SearchParamType": typeName = "Enumerations$SearchParamType"; break;
                case "SystemRestfulInteraction": typeName = "CapabilityStatement$SystemRestfulInteraction"; break;
                case "StructureMapModelMode": typeName = "StructureMap$StructureMapModelMode"; break;
                case "TaskStatus": typeName = "Task$TaskStatus"; break;
                case "AdverseEventCausality": typeName = "AdverseEvent$AdverseEventCausality"; break;
                case "AdverseEventCategory": typeName = "AdverseEvent$AdverseEventCategory"; break;
                case "MeasurePopulationType": typeName = "Measure$MeasurePopulationType"; break;
                case "SubscriptionChannelType": typeName = "Subscription$SubscriptionChannelType"; break;
                case "GraphCompartmentRule": typeName = "GraphDefinition$GraphCompartmentRule"; break;
                case "ProcedureRequestStatus": typeName = "ProcedureRequest$ProcedureRequestStatus"; break;
                case "ReferralStatus": typeName = "ReferralRequest$ReferralStatus"; break;
                case "AssertionDirectionType": typeName = "TestScript$AssertionDirectionType"; break;
                case "SlicingRules": typeName = "ElementDefinition$SlicingRules"; break;
                case "ExplanationOfBenefitStatus": typeName = "ExplanationOfBenefit$ExplanationOfBenefitStatus"; break;
                case "LinkType": typeName = "Patient$LinkType"; break;
                case "AllergyIntoleranceCriticality": typeName = "AllergyIntolerance$AllergyIntoleranceCriticality"; break;
                case "ConceptMapEquivalence": typeName = "Enumerations$ConceptMapEquivalence"; break;
                case "PropertyRepresentation": typeName = "ElementDefinition$PropertyRepresentation"; break;
                case "AuditEventAction": typeName = "AuditEvent$AuditEventAction"; break;
                case "MeasureDataUsage": typeName = "Measure$MeasureDataUsage"; break;
                case "TriggerType": typeName = "TriggerDefinition$TriggerType"; break;
                case "ActivityDefinitionCategory": typeName = "ActivityDefinition$ActivityDefinitionCategory"; break;
                case "SearchModifierCode": typeName = "SearchParameter$SearchModifierCode"; break;
                case "CompositionStatus": typeName = "Composition$CompositionStatus"; break;
                case "AppointmentStatus": typeName = "Appointment$AppointmentStatus"; break;
                case "MessageSignificanceCategory": typeName = "CapabilityStatement$MessageSignificanceCategory"; break;
                case "EventStatus": typeName = "Procedure$ProcedureStatus"; break;
                case "OperationParameterUse": typeName = "OperationDefinition$OperationParameterUse"; break;
                case "ListMode": typeName = "ListResource$ListMode"; break;
                case "ObservationStatus": typeName = "Observation$ObservationStatus"; break;
                case "qualityType": typeName = "Sequence$QualityType"; break;
                case "AdministrativeGender": typeName = "Enumerations$AdministrativeGender"; break;
                case "MeasureType": typeName = "Measure$MeasureType"; break;
                case "QuestionnaireItemType": typeName = "Questionnaire$QuestionnaireItemType"; break;
                case "StructureMapListMode": typeName = "StructureMap$StructureMapListMode"; break;
                case "StructureMapGroupTypeMode": typeName = "StructureMap$StructureMapGroupTypeMode"; break;
                case "DeviceMetricCalibrationType": typeName = "DeviceMetric$DeviceMetricCalibrationType"; break;
                case "SupplyRequestStatus": typeName = "SupplyRequest$SupplyRequestStatus"; break;
                case "EncounterLocationStatus": typeName = "Encounter$EncounterLocationStatus"; break;
                case "SupplyDeliveryStatus": typeName = "SupplyDelivery$SupplyDeliveryStatus"; break;
                case "DiagnosticReportStatus": typeName = "DiagnosticReport$DiagnosticReportStatus"; break;
                case "FlagStatus": typeName = "Flag$FlagStatus"; break;
                case "AllergyIntoleranceCertainty": typeName = "AllergyIntolerance$AllergyIntoleranceCertainty"; break;
                case "CarePlanStatus": typeName = "CarePlan$CarePlanStatus"; break;
                case "CarePlanIntent": typeName = "CarePlan$CarePlanIntent"; break;
                case "ConditionClinicalStatusCodes": typeName = "Condition$ConditionClinicalStatus"; break;
                case "ListStatus": typeName = "ListResource$ListStatus"; break;
                case "DeviceUseStatementStatus": typeName = "DeviceUseStatement$DeviceUseStatementStatus"; break;
                case "MeasureScoring": typeName = "Measure$MeasureScoring"; break;
                case "AuditEventAgentNetworkType": typeName = "AuditEvent$AuditEventAgentNetworkType"; break;
                case "AddressUse": typeName = "Address$AddressUse"; break;
                case "ConditionalDeleteStatus": typeName = "CapabilityStatement$ConditionalDeleteStatus"; break;
                case "ContactPointUse": typeName = "ContactPoint$ContactPointUse"; break;
                case "UDIEntryType": typeName = "Device$UDIEntryType"; break;
                case "DeviceMetricOperationalStatus": typeName = "DeviceMetric$DeviceMetricOperationalStatus"; break;
                case "NutritionOrderStatus": typeName = "NutritionOrder$NutritionOrderStatus"; break;
                case "ContributorType": typeName = "Contributor$ContributorType"; break;
                case "ReferenceVersionRules": typeName = "ElementDefinition$ReferenceVersionRules"; break;
                case "Use": typeName = "Claim$Use"; break;
                case "IdentityAssuranceLevel": typeName = "Person$IdentityAssuranceLevel"; break;
                case "MeasureReportStatus": typeName = "MeasureReport$MeasureReportStatus"; break;
                case "DeviceMetricColor": typeName = "DeviceMetric$DeviceMetricColor"; break;
                case "SearchEntryMode": typeName = "Bundle$SearchEntryMode"; break;
                case "ConditionalReadStatus": typeName = "CapabilityStatement$ConditionalReadStatus"; break;
                case "ConditionVerificationStatus": typeName = "Condition$ConditionVerificationStatus"; break;
                case "AllergyIntoleranceSeverity": typeName = "AllergyIntolerance$AllergyIntoleranceSeverity"; break;
                case "FinancialResourceStatusCodes": typeName = "ClaimResponse$ClaimResponseStatus"; break;
                case "OperationKind": typeName = "OperationDefinition$OperationKind"; break;
                case "ObservationRelationshipType": typeName = "Observation$ObservationRelationshipType"; break;
                case "NameUse": typeName = "HumanName$NameUse"; break;
                case "SubscriptionStatus": typeName = "Subscription$SubscriptionStatus"; break;
                case "DocumentReferenceStatus": typeName = "Enumerations$DocumentReferenceStatus"; break;
                case "CommunicationRequestStatus": typeName = "CommunicationRequest$CommunicationRequestStatus"; break;
                case "LocationMode": typeName = "Location$LocationMode"; break;
                case "repositoryType": typeName = "Sequence$RepositoryType"; break;
                case "CarePlanRelationship": typeName = "CarePlan$CarePlanRelationship"; break;
                case "LocationStatus": typeName = "Location$LocationStatus"; break;
                case "FHIRSubstanceStatus": typeName = "Substance$FHIRSubstanceStatus"; break;
                case "UnknownContentCode": typeName = "CapabilityStatement$UnknownContentCode"; break;
                case "NoteType": typeName = "Enumerations$NoteType"; break;
                case "TestReportStatus": typeName = "TestReport$TestReportStatus"; break;
                case "TestReportActionResult": typeName = "TestReport$TestReportActionResult"; break;
                case "HTTPVerb": typeName = "Bundle$HTTPVerb"; break;
                case "CodeSystemContentMode": typeName = "CodeSystem$CodeSystemContentMode"; break;
                case "ActionRelationshipType": typeName = "PlanDefinition$ActionRelationshipType"; break;
                case "EpisodeOfCareStatus": typeName = "EpisodeOfCare$EpisodeOfCareStatus"; break;
                case "RemittanceOutcome": typeName = "Enumerations$RemittanceOutcome"; break;
                case "FHIRDeviceStatus": typeName = "Device$FHIRDeviceStatus"; break;
                case "ContactPointSystem": typeName = "ContactPoint$ContactPointSystem"; break;
                case "SlotStatus": typeName = "Slot$SlotStatus"; break;
                case "PropertyType": typeName = "CodeSystem$PropertyType"; break;
                case "TypeDerivationRule": typeName = "StructureDefinition$TypeDerivationRule"; break;
                case "MedicationStatus": typeName = "Medication$MedicationStatus"; break;
                case "MedicationStatementStatus": typeName = "MedicationStatement$MedicationStatementStatus"; break;
                case "GuidanceResponseStatus": typeName = "GuidanceResponse$GuidanceResponseStatus"; break;
                case "QuantityComparator": typeName = "Quantity$QuantityComparator"; break;
                case "RelatedArtifactType": typeName = "RelatedArtifact$RelatedArtifactType"; break;
                case "DeviceStatus": typeName = "Device$DeviceStatus"; break;
                case "ContractResourceStatusCodes": typeName = "Contract$ContractStatus"; break;
                case "TestReportResultCodes": typeName = "TestReport$TestReportResult"; break;
                case "MeasureReportType": typeName = "MeasureReport$MeasureReportType"; break;
                case "SampledDataDataType": typeName = "StringType"; break;
                case "MedicationStatementTaken": typeName = "MedicationStatement$MedicationStatementTaken"; break;
                case "CompartmentType": typeName = "CompartmentDefinition$CompartmentType"; break;
                case "CompositionAttestationMode": typeName = "Composition$CompositionAttestationMode"; break;
                case "ActionRequiredBehavior": typeName = "PlanDefinition$ActionRequiredBehavior"; break;
                case "DeviceMetricCalibrationState": typeName = "DeviceMetric$DeviceMetricCalibrationState"; break;
                case "GroupType": typeName = "Group$GroupType"; break;
                case "TypeRestfulInteraction": typeName = "CapabilityStatement$TypeRestfulInteraction"; break;
                case "ActionCardinalityBehavior": typeName = "PlanDefinition$ActionCardinalityBehavior"; break;
                case "CodeSystemHierarchyMeaning": typeName = "CodeSystem$CodeSystemHierarchyMeaning"; break;
                case "MedicationStatementNotTaken": typeName = "MedicationStatement$MedicationStatementNotTaken"; break;
                case "BundleType": typeName = "Bundle$BundleType"; break;
                case "SystemVersionProcessingMode": typeName = "ExpansionProfile$SystemVersionProcessingMode"; break;
                case "FHIRDefinedType": typeName = "Enumerations$FHIRDefinedType"; break;
                case "FHIRAllTypes": typeName = "Enumerations$FHIRAllTypes"; break;
            }
            if (typeName.contains("$")) {
                typeName += "EnumFactory";
            }
            return Class.forName(String.format("%s.%s", packageName, typeName));
        }
        catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(String.format("Could not resolve type %s.%s.", packageName, typeName));
        }
    }

    @Override
    public Class resolveType(Object value) {
        if (value == null) {
            return Object.class;
        }

        if (value instanceof org.hl7.fhir.dstu3.model.Enumeration) {
            String className = ((org.hl7.fhir.dstu3.model.Enumeration)value).getEnumFactory().getClass().getSimpleName();
            try {
                return Class.forName(String.format("%s.%s", packageName, className.substring(0, className.indexOf("EnumFactory"))));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(String.format("Could not resolve type %s.%s.", packageName, className));
            }
        }

        return value.getClass();
    }

    @Override
    public void setValue(Object target, String path, Object value) {
        if (target == null) {
            return;
        }

        if (target.getClass().getSimpleName().contains("EnumFactory") && path.equals("value")) {
            return;
        }

        Class<? extends Object> clazz = target.getClass();
        try {
            String readAccessorMethodName = String.format("%s%s%s", "get", path.substring(0, 1).toUpperCase(), path.substring(1));
            String readElementAccessorMethodName = String.format("%sElement", readAccessorMethodName);
            Method readAccessor = null;
            try {
                readAccessor = clazz.getMethod(readElementAccessorMethodName);
            }
            catch (NoSuchMethodException e) {
                readAccessor = clazz.getMethod(readAccessorMethodName);
            }

            String accessorMethodName = String.format("%s%s%s", "set", path.substring(0, 1).toUpperCase(), path.substring(1));
            String elementAccessorMethodName = String.format("%sElement", accessorMethodName);
            Method accessor = null;
            try {
                accessor = clazz.getMethod(elementAccessorMethodName, readAccessor.getReturnType());
            }
            catch (NoSuchMethodException e) {
                accessor = clazz.getMethod(accessorMethodName, readAccessor.getReturnType());
            }

            try {
                accessor.invoke(target, fromJavaPrimitive(value, target));
            } catch (IllegalArgumentException e) {
                // HACK: SimpleQuantity moving in on Quantity's turf
                if (value instanceof Quantity) {
                    value = ((Quantity) value).castToSimpleQuantity(new SimpleQuantity());
                    accessor.invoke(target, value);
                }
                else {
                    throw new IllegalArgumentException(e.getMessage());
                }
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(String.format("Could not determine accessor function for property %s of type %s", path, clazz.getSimpleName()));
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(String.format("Errors occurred attempting to invoke the accessor function for property %s of type %s", path, clazz.getSimpleName()));
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(String.format("Could not invoke the accessor function for property %s of type %s", path, clazz.getSimpleName()));
        } catch (FHIRException e) {
            throw new IllegalArgumentException(String.format("The class %s is unable to resolve the type %s in the %s method",
                    target.getClass().getSimpleName(), value.getClass().getSimpleName(), getReadAccessor(target.getClass(), path).getName()));
        }

    }

    protected Field getProperty(Class clazz, String path) {
        try {
            Field field = clazz.getDeclaredField(path);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(String.format("Could not determine field for path %s of type %s", path, clazz.getSimpleName()));
        }
    }

    protected Method getReadAccessor(Class clazz, String path) {
        Field field = getProperty(clazz, path);
        String accessorMethodName = String.format("%s%s%s", "get", path.substring(0, 1).toUpperCase(), path.substring(1));
        Method accessor = null;
        try {
            accessor = clazz.getMethod(accessorMethodName);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(String.format("Could not determine accessor function for property %s of type %s", path, clazz.getSimpleName()));
        }
        return accessor;
    }

    protected Method getWriteAccessor(Class clazz, String path) {
        Field field = getProperty(clazz, path);
        String accessorMethodName = String.format("%s%s%s", "set", path.substring(0, 1).toUpperCase(), path.substring(1));
        Method accessor = null;
        try {
            accessor = clazz.getMethod(accessorMethodName, field.getType());
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(String.format("Could not determine accessor function for property %s of type %s", path, clazz.getSimpleName()));
        }
        return accessor;
    }
}
