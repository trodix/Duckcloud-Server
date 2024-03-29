package com.trodix.documentstorage.request;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class NodeRepresentationRequest {

    private String bucket;

    private String directoryPath;

    private String type;

    private List<String> aspects;

    private Map<String, Serializable> properties;

}
