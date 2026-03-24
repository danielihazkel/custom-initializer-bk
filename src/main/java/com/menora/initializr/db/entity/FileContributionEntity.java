package com.menora.initializr.db.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "file_contribution")
public class FileContributionEntity {

    public enum FileType {
        STATIC_COPY, YAML_MERGE, TEMPLATE, DELETE
    }

    public enum SubstitutionType {
        PROJECT,  // replaces {{artifactId}}, {{groupId}}, {{version}}
        PACKAGE,  // replaces {{packageName}}
        NONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** dep_id from dependency_entry, or '__common__' for files added to every project */
    @Column(name = "dependency_id", nullable = false, length = 50)
    private String dependencyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 20)
    private FileType fileType;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String content;

    @Column(name = "target_path", nullable = false, length = 500)
    private String targetPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "substitution_type", length = 20)
    private SubstitutionType substitutionType = SubstitutionType.NONE;

    /** null = all Java versions; "17" or "21" = version-specific */
    @Column(name = "java_version", length = 10)
    private String javaVersion;

    /** null = always include; set to an option ID to include only when that sub-option is selected */
    @Column(name = "sub_option_id", length = 50)
    private String subOptionId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDependencyId() { return dependencyId; }
    public void setDependencyId(String dependencyId) { this.dependencyId = dependencyId; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
    public SubstitutionType getSubstitutionType() { return substitutionType; }
    public void setSubstitutionType(SubstitutionType substitutionType) { this.substitutionType = substitutionType; }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
    public String getSubOptionId() { return subOptionId; }
    public void setSubOptionId(String subOptionId) { this.subOptionId = subOptionId; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
