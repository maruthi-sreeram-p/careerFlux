package com.careerflux.source.domain;

import com.careerflux.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A JSON job feed described as data by a portal admin, read by one generic adapter.
 *
 * <p>Holds an endpoint and where things are in the response, and nothing that
 * could change how a request is made: no method, headers, credentials or body.
 * A definition is a capability, never a permission; sources using it pass the
 * same policy gate as every other source.
 */
@Entity
@Table(name = "adapter_definitions")
public class AdapterDefinition extends BaseEntity {

    /** Every definition's key starts with this, so none can shadow a built-in adapter. */
    public static final String KEY_PREFIX = "custom-";

    @Column(name = "adapter_key", nullable = false, unique = true, length = 64)
    private String adapterKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "description", length = 600)
    private String description;

    @Column(name = "documentation_url", length = 500)
    private String documentationUrl;

    @Column(name = "endpoint_template", nullable = false, length = 500)
    private String endpointTemplate;

    @Column(name = "items_path", length = 200)
    private String itemsPath;

    @Column(name = "id_path", nullable = false, length = 200)
    private String idPath;

    @Column(name = "title_path", nullable = false, length = 200)
    private String titlePath;

    @Column(name = "location_path", length = 200)
    private String locationPath;

    @Column(name = "description_path", length = 200)
    private String descriptionPath;

    @Column(name = "apply_url_path", length = 200)
    private String applyUrlPath;

    @Column(name = "apply_url_template", length = 500)
    private String applyUrlTemplate;

    @Column(name = "posted_at_path", length = 200)
    private String postedAtPath;

    @Column(name = "employment_type_path", length = 200)
    private String employmentTypePath;

    @Column(name = "department_path", length = 200)
    private String departmentPath;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;

    public String getAdapterKey() {
        return adapterKey;
    }

    public void setAdapterKey(String adapterKey) {
        this.adapterKey = adapterKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDocumentationUrl() {
        return documentationUrl;
    }

    public void setDocumentationUrl(String documentationUrl) {
        this.documentationUrl = documentationUrl;
    }

    public String getEndpointTemplate() {
        return endpointTemplate;
    }

    public void setEndpointTemplate(String endpointTemplate) {
        this.endpointTemplate = endpointTemplate;
    }

    public String getItemsPath() {
        return itemsPath;
    }

    public void setItemsPath(String itemsPath) {
        this.itemsPath = itemsPath;
    }

    public String getIdPath() {
        return idPath;
    }

    public void setIdPath(String idPath) {
        this.idPath = idPath;
    }

    public String getTitlePath() {
        return titlePath;
    }

    public void setTitlePath(String titlePath) {
        this.titlePath = titlePath;
    }

    public String getLocationPath() {
        return locationPath;
    }

    public void setLocationPath(String locationPath) {
        this.locationPath = locationPath;
    }

    public String getDescriptionPath() {
        return descriptionPath;
    }

    public void setDescriptionPath(String descriptionPath) {
        this.descriptionPath = descriptionPath;
    }

    public String getApplyUrlPath() {
        return applyUrlPath;
    }

    public void setApplyUrlPath(String applyUrlPath) {
        this.applyUrlPath = applyUrlPath;
    }

    public String getApplyUrlTemplate() {
        return applyUrlTemplate;
    }

    public void setApplyUrlTemplate(String applyUrlTemplate) {
        this.applyUrlTemplate = applyUrlTemplate;
    }

    public String getPostedAtPath() {
        return postedAtPath;
    }

    public void setPostedAtPath(String postedAtPath) {
        this.postedAtPath = postedAtPath;
    }

    public String getEmploymentTypePath() {
        return employmentTypePath;
    }

    public void setEmploymentTypePath(String employmentTypePath) {
        this.employmentTypePath = employmentTypePath;
    }

    public String getDepartmentPath() {
        return departmentPath;
    }

    public void setDepartmentPath(String departmentPath) {
        this.departmentPath = departmentPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
