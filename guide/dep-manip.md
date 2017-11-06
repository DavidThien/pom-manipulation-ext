---
title: "Dependency Manipulation"
---

* Contents
{:toc}

### Overview

PME can override a set of dependency versions using a remote source which may be either a pom (BOM) file or a remote REST endpoint.

### Dependency Source

#### BOM and REST

There are two sources of dependencies used to align to in PME. The property `dependencySource` is used to alter the behaviour of how PME handles the multiple sources of dependency information. The `BOM` value (the default and the behaviour if this property is not specified) is that PME will use the BOM (i.e. Remote POM) source. Alternatively the `REST` source may be specified to use only the REST Endpoint information. However by setting the property to either `RESTBOM` or `BOMREST` it will instead merge the two sets of values. With `RESTBOM` precendence is given to the REST information and for `BOMREST` precendence is given to the BOM information.

#### Remote POM

By default, all dependencies listed in the remote pom will be added to the current build. This has the effect of overriding matching transitive dependencies, as well as those specified directly in the pom.

Use the `dependencyManagement` property to list your BOMs:

    mvn install -DdependencyManagement=org.foo:my-dep-pom:1.0

Multiple remote dependency-management poms can be specified using a comma separated list of GAVs (groupId, artifactId, version). The poms are specified in order of priority, so if the remote boms contain some of the same dependencies, the versions listed in the first bom in the list will be used.

    mvn install -DdependencyManagement=org.foo:my-dep-pom:1.0,org.bar:my-dep-pom:2.0


#### REST Endpoint

Alternatively, rather than using a remote BOM file as a source, it is possible to instruct PME to prescan the project, collect up all group:artifact:version's used and call a REST endpoint using the endpoint property `restURL` (provided from the Dependency Analysis tool [here](https://github.com/project-ncl/dependency-analysis)) **and** specifying `dependencySource` of `REST`, which will then return a list of possible new versions. Note that the URL should be the subset of the endpoint e.g.

    http://foo.bar.com/da/rest/v-1

PME will then call the following endpoints

    reports/lookup/gavs
    listings/blacklist/ga

It will initially call the `lookup/gavs` endpoint. By default PME will pass *all* the GAVs to the endpoint ; it can be configured to split them into initial batches via `-DrestMaxSize=<...>`. If the endpoint returns a 504 timeout the batch is automatically split into smaller chunks in an attempt to reduce load on the endpoint. It will by default chunk down to size of 4 before aborting. This can be configured with `-DrestMinSize=<...>`.

Finally it will call the `blacklist/ga` endpoint in order to check that the version being build is not in the blacklist.

The lookup REST endpoint should follow:

<table>
<tr>
   <th id="Parameters">Parameters</th>
   <th id="Returns">Returns</th>
</tr>
<tr>
<td>
   <pre lang="xml" style="font-size: 10px">
[
    {
        "groupId": "org.foo",
        "artifactId": "bar",
        "version": "1.0.0.Final"
    },
    ...
]
    </pre>
</td>
<td>
  <pre lang="xml" style="font-size: 10px">
[
    {
        "groupId": "org.foo",
        "artifactId": "bar",
        "version": "1.0.0.Final",
        "availableVersions": ["1.0.0.Final-rebuild-2",
"1.0.0.Final-rebuild-1", "1.0.1.Final-rebuild-1"],
        "bestMatchVersion": "1.0.0.Final-rebuild-2",
        "blacklisted": false,
        "whitelisted": true
    },
    ...
]  </pre>
</td>
</tr>
</table>

The blacklist REST endpoint should follow:

<table>
<tr>
   <th id="Parameters">Parameters</th>
   <th id="Returns">Returns</th>
</tr>
<tr>
<td>
   <pre lang="xml" style="font-size: 10px">

    "groupid": "org.foo",
    "artifactid": "bar"

    </pre>
</td>
<td>
  <pre lang="xml" style="font-size: 10px">
[
    {
        "groupId": "org.foo",
        "artifactId": "bar",
        "version": "1.0.0.Final-rebuild-1",
    },
    ...
]  </pre>
</td>
</tr>
</table>

**NOTE:** For existing dependencies that reference a property, PME will update this property with the new version. If the property can't be found (e.g. it was inherited), a new one will be injected at the top level. This update of the property's value **may** implicitly align other dependencies using the same property that were not explicitly requested to be aligned.

### Direct Dependencies

By default the extension will override dependencies using declarations from the remote BOM. However, by setting the property `overrideDependencies` to `false`, the behavior can be disabled:

    mvn install -DdependencyManagement=org.foo:my-dep-pom:1.0 -DoverrideDependencies=false

Note that this will still alter any external parent references.

<table bgcolor="#ff3333">
<tr>
<td>
    <b>NOTE</b> : This option is deprecated as of PME 2.12 (July 2017) and may be removed in a future release.
</td>
</tr>
</table>

### Direct/Transitive Dependencies

<table bgcolor="#ffff00">
<tr>
<td>
    <b>NOTE</b> : As of PME 2.13 (October 2017) the default for <i>overrideTransitive</i> has changed from true to false.
</td>
</tr>
</table>

The extension can inject all dependencies declared in the remote BOM. This will also override dependencies that are not directly specified in the project. If these transitive dependencies should not be overridden, the option `overrideTransitive` can be set to `false` to disable this feature.

    mvn install -DdependencyManagement=org.foo:my-dep-pom:1.0 -DoverrideTransitive=false


### Parent Version Override

PME will also change any parent reference it finds that matches an entry in the remote BOM.

For example:

      <parent>
         <groupId>org.switchyard</groupId>
         <artifactId>switchyard-parent</artifactId>
         <version>2.0.0.Alpha1</version>

will change to:

      <parent>
         <groupId>org.switchyard</groupId>
         <artifactId>switchyard-parent</artifactId>
         <version>2.0.0.Alpha1-rebuild-1</version>


### Exclusions and Overrides

In a multi-module build it is considered good practice to coordinate dependency version among the modules using dependency management.  In other words, if module A and B both use dependency X, both modules should use the same version of dependency X.  Therefore, the default behaviour of this extension is to use a single set of dependency versions applied to all modules.

It is possible to flexibly override or exclude a dependency globally or on a per module basis. The property starts with `dependencyExclusion.` and has the following format:

    mvn install -DdependencyExclusion.[groupId]:[artifactId]@[moduleGroupId]:[moduleArtifactId]=[version] | ,+[group:artifact]...

**NOTE:** `dependencyOverride` is an alias for `dependencyExclusion` and functions _exactly the same_. If both are set then they will be merged and an error thrown if they clash.

**NOTE:** Multiple exclusions may be added using multiple instances of `-DdependencyExclusion...`.

#### Global Version Override

Sometimes it is more convenient to use the command line rather than a BOM. Therefore extending the above it is possible to set the version of a dependency via:

    mvn install -DdependencyOverride.junit:junit@*=4.10-rebuild-10

This will, throughout the entire project (due to the wildcard), apply the explicit 4.10-rebuild-10 version to the junit:junit dependency.

**NOTE:** Explicit overrides like this will take precedence over strict alignment and the BOM.


#### Per-Module Version Override

However, there are certain cases where it is useful to use different versions of the same dependency in different modules.  For example, if the project includes integration code for multiple versions of a particular API. In that case it is possible to apply a version override to a specific module of a multi-module build. For example to apply an explicit dependency override only to module B of project foo.

    mvn install -DdependencyOverride.junit:junit@org.foo:moduleB=4.10

**NOTE:** Explicit overrides like this will take precedence over strict alignment and the BOM.


#### Per-Module Prevention of Override

It is also possible to **prevent overriding dependency versions** on a per module basis:

    mvn install -DdependencyOverride.[groupId]:[artifactId]@[moduleGroupId]:[moduleArtifactId]=

For example:

    mvn install -DdependencyOverride.junit:junit@org.foo:moduleB=

#### Override Prevention with Wildcards

Likewise, you can prevent overriding a dependency version across the entire project using a wildcard:

    mvn install -DdependencyOverride.[groupId]:[artifactId]@*=

For example:

    mvn install -DdependencyOverride.junit:junit@*=

Or, you can prevent overriding a dependency version across the entire project where the groupId matches, using multiple wildcards:

    mvn install -DdependencyOverride.[groupId]:*@*=

For example:

    mvn install -DdependencyOverride.junit:*@*=

#### Per Module Override Prevention with Wildcards

Linking the two prior concepts it is also possible to prevent overriding using wildcards on a per-module basis e.g.

    mvn install -DdependencyOverride.*:*@org.foo:moduleB=

This will prevent any alignment within the org.foo:moduleB.

    mvn install -DdependencyOverride.*:*@org.foo:*=

This will prevent any alignment within org.foo and all sub-modules within that.

#### Dependency Exclusion Addition

It is also possible to inject specific exclusions into a dependency. For instance

    mvn install -DdependencyOverride.junit:junit@*=4.10-rebuild-1,+slf4j:slf4j

will, as per above, apply the explicit 4.10-rebuild-10 version to the junit:junit dependency but also add an exclusion e.g.

    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.1</version>
      <exclusions>
        <exclusion>
          <artifactId>slf4j</artifactId>
          <groupId>slf4j</groupId>
        </exclusion>
      </exclusions>
    </dependency>

Multiple exclusions to a dependency may be added using comma separators e.g.

    mvn install -DdependencyOverride.junit:junit@*=+slf4j:slf4j,+commons-lang:commons-lang


### Strict Mode Version Alignment

<table bgcolor="#ffff00">
<tr>
<td>
    <b>NOTE</b> : As of PME 2.13 (October 2017) the default for <i>strictAlignment</i> has changed from false to true.
</td>
</tr>
</table>

When aligning dependency versions to some shared standard, it's possible to introduce incompatibilities that stem from changing the version. This may be due to unexpected API changes. While _in general_ it might be safe to revise a dependency's version from 1.5 to 1.5.1, it may not be safe to revise it to 2.0, or even 1.6.

By default strict-mode version alignment checks are enabled. This is recommended for cases where this is a concern, and for dependencies whose versions are aligned via a BOM or REST source (not via explicit overrides, as explained above).

Strict-mode alignment will detect cases where the adjusted version, once OSGi and suffix are handled, does not match with the old version, **not** do the alignment and report the mismatch as a warning in the build's console output. For example, if the incremental or manual suffix is configured to be `rebuild` then these are valid changes.

| Original Version | Potential New Versions |
| --- | --- |
| 2.6 | 2.6-rebuild-2 ; 2.6.0.rebuild-4 |
| 3 | 3.0.0.rebuild-1 ; 3-rebuild-1 |
| 3.1 | 3.1.0.rebuild-1 ; 3.1-rebuild-1 |

Note that it will not consider 3 -> 3.1 as a valid transition.

If required, strict-mode version alignment checks can be disabled using:

    mvn install -DstrictAlignment=false

If the build should fail when strict-mode checks are violated set `strictViolationFails` property to true (default: false):

    mvn install -DstrictAlignment=true -DstrictViolationFails=true

This will cause the build to fail with a ManipulationException, and prevent the extension from rewriting any POM files.

**NOTE:** dependency exclusions they will not work if the dependency uses a version property that has been changed by another dependency modification. Explicit version override will overwrite the property value though.

<table bgcolor="#ffff00">
<tr>
<td>
    <b>NOTE</b> : As of PME 2.13 (October 2017) the default for <i>strictAlignmentIgnoreSuffix</i> has changed from false to true.
</td>
</tr>
</table>

The property `strictAlignmentIgnoreSuffix` will mean that the comparison will ignore the suffix depicted by `version.incremental.suffix` or `version.suffix` during version comparisons. It will also only allow alignment to a higher incrementing suffix e.g.

    3.1.0.Final-rebuild-1 --> 3.1.0.Final-rebuild-3

### Property Clash Replacement

When replacing a dependency PME will trace back and update the property value. However there are scenarios where the dependency versions returned from either the BOM or the REST source can be different even though they refer to the same property e.g.

    propertyX = 1.0

    org.foo:bar1:$propertyX
    org.foo:bar2:$propertyX

And the REST source returns 1.0.rebuild-2 for bar1 and rebuild-4 for bar2. In this case PME will detect the clash and throw an error. It is possible to configure PME so it will not update the property and continue by setting `propertyClashFails` to false (default: true).

### Dependency Relocations

In order to handle the situation where one GAV is changed to another (e.g. from community to product) the relocation manipulator can be used. An optional version component may be added; the version will override any prior version used in the dependency. Note this is akin to using the dependencyOverride functionality with an explicit version. The artifact override is optional.

    -DdependencyRelocations.oldGroupId:[oldArtifact]@newGroupId:[newArtifactId]=[version],...

**NOTE:** Multiple relocations may be added using multiple instances of `-DdependencyRelocation...`.

### Dependency Removal

If the property `-DdependencyRemoval=group:artifact,....` is set, PME will remove the specified dependency from the POM files. The argument should be a comma separated list of group:artifact.

### BOM Generation

If the property `-DbomBuilder=true` is set, then the PME BOM Builder will be activated. This will deploy a new POM to the repository under the GAV `<project-top-level-groupId>.<project-top-level-artifactId>:pme-bom:<version>` e.g. `org.projectOne.artifactOne:pme-bom:1.0.0`. It will contain a list of all adjusted modules and is suitable for use as a remote BOM. As it has a predictable name it may be used in a build of a subsequent project to align to this one e.g.

    PWD=<project two> ; mvn -DdependencyManagement=org.projectOne.artifactOne:pme-bom:1.0.0 -Dversion.suffix=rebuild-1 clean install

**Note:** The new BOM will be installed and deployed via a custom plugin that is added to the top level project. The plugin is configured to only run on the top level POM and will not fail if the BOM POM does not exist.