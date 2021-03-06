/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.maven.core.config.ProcessorConfig;
import io.fabric8.maven.core.config.Profile;
import org.apache.maven.shared.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for dealing with profiles.
 *
 * @author roland
 * @since 25/07/16
 */
public class ProfileUtil {

    private ProfileUtil() {}

    private static final Logger log = LoggerFactory.getLogger(ProfileUtil.class);

    // Allowed profile names
    public static final String[] PROFILE_FILENAMES = {"profiles%s.yml", "profiles%s.yaml", "profiles%s"};

    // Mapper for handling YAML formats
    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    // Default profile which will be always there
    public static final String DEFAULT_PROFILE = "default";

    /**
     * Find a profile. Profiles are looked up at various locations:
     *
     * <ul>
     *     <li>A given directory with the name profiles.yml (and variations, {@link #findProfile(String, File)}</li>
     * </ul>
     * @param profileArg the profile's name
     * @param resourceDir a directory to check for profiles.
     * @return the profile found or the default profile if none of this name is given
     * @throws IOException
     */
    public static Profile findProfile(String profileArg, File resourceDir) throws IOException {
        try {
            String profile = profileArg == null ? DEFAULT_PROFILE : profileArg;
            Profile profileFound = lookup(profile, resourceDir);
            if (profileFound != null) {
                if(profileFound.getParentProfile() != null) {
                    profileFound = inheritFromParentProfile(profileFound, resourceDir);
                    log.info(profileFound + " inheriting resources from " + profileFound.getParentProfile());
                }
                return profileFound;
            } else {
                throw new IllegalArgumentException("No profile '" + profile + "' defined");
            }
        } catch (IOException e) {
            throw new IOException("Error while looking up profile " + profileArg + ": " + e.getMessage(),e);
        }
    }

    private static Profile inheritFromParentProfile(Profile aProfile, File resourceDir) throws IOException {
        Profile aParentProfile = lookup(aProfile.getParentProfile(), resourceDir);
        if(aParentProfile != null) {
            aProfile.setEnricherConfig(ProcessorConfig.mergeProcessorConfigs(aProfile.getEnricherConfig(), aParentProfile.getEnricherConfig()));
            aProfile.setGeneratorConfig(ProcessorConfig.mergeProcessorConfigs(aProfile.getGeneratorConfig(), aParentProfile.getGeneratorConfig()));
            aProfile.setWatcherConfig(ProcessorConfig.mergeProcessorConfigs(aProfile.getWatcherConfig(), aParentProfile.getWatcherConfig()));
        } else {
            throw new IllegalArgumentException("No parent profile '" + aProfile.getParentProfile() + "' defined");
        }
        return aProfile;
    }

    /**
     * Find an enricher or generator config, possibly via a profile and merge it with a given configuration.
     *
     * @param configExtractor how to extract the config from a profile when found
     * @param profile the profile name (can be null, then no profile is used)
     * @param resourceDir resource directory where to lookup the profile (in addition to a classpath lookup)
     * @return the merged configuration which can be empty if no profile is given
     * @param config the provided configuration
     * @throws IOException
     */
    public static ProcessorConfig blendProfileWithConfiguration(ProcessorConfigurationExtractor configExtractor,
                                                                String profile,
                                                                File resourceDir,
                                                                ProcessorConfig config) throws IOException {
        // Get specified profile or the default profile
        ProcessorConfig profileConfig = extractProcesssorConfiguration(configExtractor, profile, resourceDir);

        return ProcessorConfig.mergeProcessorConfigs(config, profileConfig);
    }


    /**
     * Lookup profiles from a given directory and merge it with a profile of the
     * same name found in the classpath
     *
     * @param name name of the profile to lookup
     * @param directory directory to lookup
     * @return Profile found or null
     * @throws IOException if somethings fails during lookup
     */
    public static Profile lookup(String name, File directory) throws IOException {
        // First check from the classpath, these profiles are used as a basis
        List<Profile> profiles = readProfileFromClasspath(name);

        File profileFile = findProfileYaml(directory);
        if (profileFile != null) {
            List<Profile> fileProfiles = fromYaml(new FileInputStream(profileFile));
            for (Profile profile : fileProfiles) {
                if (profile.getName().equals(name)) {
                    profiles.add(profile);
                    break;
                }
            }
        }
        // "larger" orders are "earlier" in the list
        Collections.sort(profiles, Collections.<Profile>reverseOrder());
        return mergeProfiles(profiles);
    }

    private static ProcessorConfig extractProcesssorConfiguration(ProcessorConfigurationExtractor extractor,
                                                                 String profile,
                                                                 File resourceDir) throws IOException {
        Profile profileFound = findProfile(profile, resourceDir);
        return extractor.extract(profileFound);
    }


    private static Profile mergeProfiles(List<Profile> profiles) {
        Profile ret = null;
        for (Profile profile : profiles) {
            if (profile != null) {
                if (ret == null) {
                    ret = new Profile(profile);
                } else {
                    ret = new Profile(ret, profile);
                }
            }
        }
        return ret;
    }

    // Read all default profiles first, then merge in custom profiles found on the classpath
    private static List<Profile> readProfileFromClasspath(String name) throws IOException {
        List<Profile> ret = new ArrayList<>();
        ret.addAll(readAllFromClasspath(name, "default"));
        ret.addAll(readAllFromClasspath(name, ""));
        return ret;
    }

    /**
     * Read all profiles found in the classpath.
     *
     * @param name name of the profile to lookup
     * @param ext to use (e.g. 'default' for checking 'profile-default.yml'. Can also be null or empty.
     * @return all profiles with this name stored in files with this extension
     *
     * @throws IOException if reading of a profile fails
     */
    public static List<Profile> readAllFromClasspath(String name, String ext) throws IOException {
        List<Profile > ret = new ArrayList<>();
        for (String location : getMetaInfProfilePaths(ext)) {
            for (String url : ClassUtil.getResources(location)) {
                for (Profile profile : fromYaml(new URL(url).openStream())) {
                    if (name.equals(profile.getName())) {
                        ret.add(profile);
                    }
                }
            }
        }
        return ret;
    }

    // ================================================================================

    // check for various variations of profile files
    private static File findProfileYaml(File directory) {
        for (String profileFile : PROFILE_FILENAMES) {
            File ret = new File(directory, String.format(profileFile, ""));
            if (ret.exists()) {
                return ret;
            }
        }
        return null;
    }

    // prepend meta-inf location
    private static List<String> getMetaInfProfilePaths(String ext) {
        List<String> ret = new ArrayList<>(PROFILE_FILENAMES.length);
        for (String p : PROFILE_FILENAMES) {
            ret.add("META-INF/fabric8/" + getProfileFileName(p,ext));
        }
        return ret;
    }

    private static String getProfileFileName(String fileName, String ext) {
        return String.format(fileName, StringUtils.isNotBlank(ext) ? "-" + ext : "");
    }

    /**
     * Load a profile from an input stream. This must be in YAML format
     *
     * @param is inputstream to read the profile from
     * @return the de-serialized profile
     * @throws IOException if deserialization fails
     */
    public static List<Profile> fromYaml(InputStream is) throws IOException {
        TypeReference<List<Profile>> typeRef = new TypeReference<List<Profile>>() {};
        return mapper.readValue(is, typeRef);
    }

    // ================================================================================

    // Use to select either a generator or enricher config
    public interface ProcessorConfigurationExtractor {
        ProcessorConfig extract(Profile profile);
    }

    /**
     * Get the generator configuration
     */
    public final static ProcessorConfigurationExtractor GENERATOR_CONFIG = new ProcessorConfigurationExtractor() {
        @Override
        public ProcessorConfig extract(Profile profile) {
            return profile.getGeneratorConfig();
        }
    };

    /**
     * Get the enricher configuration
     */
    public final static ProcessorConfigurationExtractor ENRICHER_CONFIG = new ProcessorConfigurationExtractor() {
        @Override
        public ProcessorConfig extract(Profile profile) {
            return profile.getEnricherConfig();
        }
    };

    /**
     * Get the watcher configuration
     */
    public final static ProcessorConfigurationExtractor WATCHER_CONFIG = new ProcessorConfigurationExtractor() {
        @Override
        public ProcessorConfig extract(Profile profile) {
            return profile.getWatcherConfig();
        }
    };

}
