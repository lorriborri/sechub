// SPDX-License-Identifier: MIT
package com.mercedesbenz.sechub.commons.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SecHubDataConfiguration {

    private List<SecHubSourceDataConfiguration> sources = new ArrayList<>();
    private List<SecHubBinaryDataConfiguration> binaries = new ArrayList<>();

    public List<SecHubSourceDataConfiguration> getSources() {
        return sources;
    }

    public List<SecHubBinaryDataConfiguration> getBinaries() {
        return binaries;
    }

}
