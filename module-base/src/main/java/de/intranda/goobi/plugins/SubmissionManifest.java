package de.intranda.goobi.plugins;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.Data;

@Data
@JsonPropertyOrder({ "SubmissionManifestVersion", "SubmissionSet", "SubmittingOrganization", "OrganizationIdentifier", "ContractNumber", "Contact", "ContactRole",
    "ContactEmail", "TransferCurator", "TransferCuratorEmail", "SubmissionName", "SubmissionDescription", "RightsHolder", "Rights",
    "RightsDescription", "License", "AccessRights", "DataSourceSystem", "MetadataFile", "MetadataFileFormat", "CallbackParams" })
public class SubmissionManifest {

    @JsonProperty("SubmissionManifestVersion")
    private String submissionManifestVersion;
    @JsonProperty("SubmissionSet")
    private String submissionSet;
    @JsonProperty("SubmittingOrganization")
    private String submittingOrganization;
    @JsonProperty("OrganizationIdentifier")
    private String organizationIdentifier;
    @JsonProperty("ContractNumber")
    private String contractNumber;
    @JsonProperty("Contact")
    private String contact;
    @JsonProperty("ContactRole")
    private String contactRole;
    @JsonProperty("ContactEmail")
    private String contactEmail;
    @JsonProperty("TransferCurator")
    private String transferCurator;
    @JsonProperty("TransferCuratorEmail")
    private String transferCuratorEmail;
    @JsonProperty("SubmissionName")
    private String submissionName;
    @JsonProperty("SubmissionDescription")
    private String submissionDescription;
    @JsonProperty("RightsHolder")
    private String rightsHolder;
    @JsonProperty("Rights")
    private String rights;
    @JsonProperty("RightsDescription")
    private String rightsDescription;
    @JsonProperty("License")
    private String license;
    @JsonProperty("AccessRights")
    private String accessRights;
    @JsonProperty("DataSourceSystem")
    private String dataSourceSystem;
    @JsonProperty("MetadataFile")
    private String metadataFile;
    @JsonProperty("MetadataFileFormat")
    private String metadataFileFormat;
    @JsonProperty("CallbackParams")
    private CallbackParams callbackParams;

}
