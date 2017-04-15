/*
 * Copyright (C) 2012 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.manip.impl;

import org.apache.commons.lang.StringUtils;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.ext.manip.ManipulationException;
import org.commonjava.maven.ext.manip.ManipulationSession;
import org.commonjava.maven.ext.manip.model.Project;
import org.commonjava.maven.ext.manip.resolver.GalleyAPIWrapper;
import org.commonjava.maven.ext.manip.state.VersioningState;
import org.commonjava.maven.ext.manip.util.PropertiesUtils;
import org.commonjava.maven.galley.maven.GalleyMavenException;
import org.commonjava.maven.galley.maven.model.view.meta.MavenMetadataView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.commonjava.maven.ext.manip.impl.Version.findHighestMatchingBuildNumber;
import static org.commonjava.maven.ext.manip.util.IdUtils.gav;

/**
 * Component that calculates project version modifications, based on configuration stored in {@link VersioningState}.
 * Snapshots may/may not be preserved, and either a static or incremental (calculated) version qualifier may / may not
 * be incorporated in the version. The calculator strives for OSGi compatibility, so the use of '.' and '-' qualifier
 * separators will vary accordingly. See: http://www.aqute.biz/Bnd/Versioning and
 * http://www.osgi.org/wiki/uploads/Links/SemanticVersioning.pdf for an explanation of OSGi versioning.
 *
 * @author jdcasey
 */
@Component( role = VersionCalculator.class )
public class VersionCalculator
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    // Used by getMetadataVersions
    @Requirement
    private GalleyAPIWrapper readerWrapper;

    // For Guice
    protected VersionCalculator()
    {
    }

    // Only used by test code
    public VersionCalculator( final GalleyAPIWrapper readerWrapper )
    {
        this.readerWrapper = readerWrapper;
    }

    /**
     * Calculate any project version changes for the given set of projects, and return them in a Map keyed by project
     * GA.
     *
     * @param projects the Projects to adjust.
     * @param session the container session.
     * @return a collection of GAV : new Version
     * @throws ManipulationException if an error occurs.
     */
    public Map<ProjectVersionRef, String> calculateVersioningChanges( final List<Project> projects,
                                                                      final ManipulationSession session )
        throws ManipulationException
    {
        final VersioningState state = session.getState( VersioningState.class );
        final Map<ProjectVersionRef, String> versionsByGAV = new HashMap<>();
        final Set<String> vesionsWithBuildNums = new HashSet<>();

        for ( final Project project : projects )
        {
            String originalVersion = project.getVersion();
            originalVersion = PropertiesUtils.resolveProperties( projects, originalVersion);
            String modifiedVersion =
                calculate( project.getGroupId(), project.getArtifactId(), originalVersion, session );

            if ( state.osgi() )
            {
                modifiedVersion = Version.getOsgiVersion( modifiedVersion );
            }

            versionsByGAV.put( project.getKey(), modifiedVersion );

            if ( Version.hasBuildNumber( modifiedVersion ) )
            {
                vesionsWithBuildNums.add( modifiedVersion );
            }
        }

        // Have to loop through the versions a second time to make sure that the versions are in sync
        // between projects in the reactor.
        for ( final Project project : projects )
        {
            final String originalVersion = project.getVersion();

            String modifiedVersion = versionsByGAV.get( project.getKey() );

            // If there is only a single version there is no real need to try and find the highest matching.
            // This also fixes the problem where there is a single version and leading zeros.
            if (vesionsWithBuildNums.size() > 1)
            {
                int buildNumber = findHighestMatchingBuildNumber( modifiedVersion, vesionsWithBuildNums );

                // If the buildNumber is greater than zero, it means we found a match and have to
                // set the build number to avoid version conflicts.
                if ( buildNumber > 0 )
                {
                    String paddedBuildNum =
                            StringUtils.leftPad( Integer.toString( buildNumber ), state.getIncrementalSerialSuffixPadding(), '0' );
                    modifiedVersion = Version.setBuildNumber( modifiedVersion, paddedBuildNum );
                }
            }

            vesionsWithBuildNums.add( modifiedVersion );
            logger.debug( gav( project ) + " has updated version: {}. Marking for rewrite.", modifiedVersion );

            if ( !originalVersion.equals( modifiedVersion ) )
            {
                versionsByGAV.put(  project.getKey(), modifiedVersion );
            }
        }

        return versionsByGAV;
    }

    /**
     * Calculate the version modification for a given GAV.
     *
     * @param groupId the groupId to search for
     * @param artifactId the artifactId to search for.
     * @param version the original version to search for.
     * @param session the container session.
     * @return a Version object allowing the modified version to be extracted.
     * @throws ManipulationException if an error occurs.
     */
    protected String calculate( final String groupId, final String artifactId, final String version,
                                 final ManipulationSession session )
        throws ManipulationException
    {
        final VersioningState state = session.getState( VersioningState.class );

        final String incrementalSuffix = state.getIncrementalSerialSuffix();
        final String staticSuffix = state.getSuffix();
        final String override = state.getOverride();

        logger.debug( "Got the following version:\n  Original version: " + version );
        logger.debug( "Got the following version suffixes:\n  Static: " + staticSuffix + "\n  Incremental: " +
            incrementalSuffix );
        logger.debug( "Got the following override:\n  Version: " + override);

        String newVersion = version;

        if ( override != null )
        {
            newVersion = override;
        }

        if ( staticSuffix != null )
        {
            newVersion = Version.appendQualifierSuffix( newVersion, staticSuffix );
            if ( !state.preserveSnapshot() )
            {
                newVersion = Version.removeSnapshot( newVersion );
            }
        }
        else if ( incrementalSuffix != null )
        {
            final Set<String> versionCandidates = this.getVersionCandidates(state, groupId, artifactId);

            newVersion = Version.appendQualifierSuffix( newVersion, incrementalSuffix );
            int highestRemoteBuildNumPlusOne = findHighestMatchingBuildNumber( newVersion, versionCandidates ) + 1;

            if ( highestRemoteBuildNumPlusOne > Version.getIntegerBuildNumber( newVersion ) )
            {
                String paddedBuildNumber = StringUtils.leftPad( Integer.toString( highestRemoteBuildNumPlusOne ),
                        state.getIncrementalSerialSuffixPadding(), '0' );
                newVersion = Version.setBuildNumber( newVersion, paddedBuildNumber );
            }
            if ( !state.preserveSnapshot() )
            {
                newVersion = Version.removeSnapshot( newVersion );
            }
        }

        return newVersion;
    }

    /**
     * Find matching version strings in the remote repo.
     */
    private Set<String> getVersionCandidates(VersioningState state, String groupId, String artifactId)
            throws ManipulationException
    {
        final Set<String> versionCandidates = new HashSet<>();

        Map<ProjectRef, Set<String>> rm = state.getRESTMetadata();
        if ( rm != null)
        {
            // If the REST Client has prepopulated incremental data use that instead of the examining the repository.
            if (!rm.isEmpty())
            {
                // Use preloaded metadata from remote repository, loaded via a REST Call.
                if (rm.get( new SimpleProjectRef( groupId, artifactId ) ) != null)
                {
                    versionCandidates.addAll( rm.get( new SimpleProjectRef( groupId, artifactId ) ) );
                }
            }
        }
        else
        {
            // Load metadata from local repository
            versionCandidates.addAll( getMetadataVersions( groupId, artifactId ) );
        }
        return versionCandidates;

    }

    /**
     * Accumulate all available versions for a given GAV from all available repositories.
     * @param groupId the groupId to search for
     * @param artifactId the artifactId to search for
     * @return Collection of versions for the specified group:artifact
     * @throws ManipulationException if an error occurs.
     */
    private Set<String> getMetadataVersions( final String groupId, final String artifactId )
        throws ManipulationException
    {
        logger.debug( "Reading available versions from repository metadata for: " + groupId + ":" + artifactId );

        try
        {
            final MavenMetadataView metadataView =
                readerWrapper.readMetadataView( new SimpleProjectRef( groupId, artifactId ) );

            final List<String> versions =
                metadataView.resolveXPathToAggregatedStringList( "/metadata/versioning/versions/version", true, -1 );

            return new HashSet<>( versions );
        }
        catch ( final GalleyMavenException e )
        {
            throw new ManipulationException( "Failed to resolve metadata for: %s:%s.", e, groupId, artifactId );
        }
    }
}
