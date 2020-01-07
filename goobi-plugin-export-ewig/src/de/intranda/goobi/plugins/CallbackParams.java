package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class CallbackParams {
    private String endpoint;
    private Integer processId;
    private Integer stepId;
}
