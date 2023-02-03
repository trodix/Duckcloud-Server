package com.trodix.documentstorage.service;

import java.io.Serializable;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.trodix.documentstorage.mapper.NodeMapper;
import com.trodix.documentstorage.model.ContentModel;
import com.trodix.documentstorage.model.NodeRepresentation;
import com.trodix.documentstorage.persistance.dao.NodeDAO;
import com.trodix.documentstorage.persistance.entity.Aspect;
import com.trodix.documentstorage.persistance.entity.Node;
import com.trodix.documentstorage.persistance.entity.NodeIndex;
import com.trodix.documentstorage.persistance.entity.Property;
import com.trodix.documentstorage.persistance.entity.Type;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
public class NodeService {

    private final StorageService storageService;

    private final NodeDAO nodeDAO;

    private final QNameService qnameService;

    private final TypeService typeService;

    private final AspectService aspectService;

    private final PropertyService propertyService;

    private final NodeIndexerService nodeIndexerService;

    private final NodeMapper nodeMapper;

    public Node nodeRepresentationToNode(final NodeRepresentation nodeRepresentation) throws IllegalArgumentException {

        final Type type = typeService.stringToType(nodeRepresentation.getType());

        final List<Aspect> aspects = new ArrayList<>();
        nodeRepresentation.getAspects().stream().forEach(aspectString -> {
            final Aspect aspect = aspectService.stringToAspect(aspectString);
            aspects.add(aspect);
        });

        final List<Property> properties = new ArrayList<>();

        nodeRepresentation.getProperties().forEach((key, value) -> {
            Property property;
            try {
                property = propertyService.createProperty(qnameService.stringToQName(key), value);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }

            properties.add(property);
        });

        final Node node = new Node();
        node.setBucket(nodeRepresentation.getBucket());
        node.setDirectoryPath(nodeRepresentation.getDirectoryPath());
        node.setUuid(StringUtils.isBlank(nodeRepresentation.getUuid()) ? UUID.randomUUID().toString() : nodeRepresentation.getUuid());
        node.setType(type);
        node.setAspects(aspects);
        node.setProperties(properties);

        return node;
    }

    public NodeRepresentation nodeToNodeRepresentation(final Node node) {

        final List<String> aspects = new ArrayList<>();
        node.getAspects().stream().forEach(aspect -> aspects.add(aspectService.aspectToString(aspect)));

        final Map<String, Serializable> properties = new HashMap<>();
        node.getProperties().stream().forEach(property -> {
            final String propertyName = qnameService.qnameToString(property.getQname());
            final Serializable propertyValue = propertyService.getPropertyValue(property);

            properties.put(propertyName, propertyValue);
        });

        final NodeRepresentation nodeRepresentation = new NodeRepresentation();
        nodeRepresentation.setBucket(node.getBucket());
        nodeRepresentation.setDirectoryPath(node.getDirectoryPath());
        nodeRepresentation.setUuid(node.getUuid());
        nodeRepresentation.setType(typeService.typeToString(node.getType()));
        nodeRepresentation.setAspects(aspects);
        nodeRepresentation.setProperties(properties);

        return nodeRepresentation;
    }

    public NodeRepresentation persistNode(final NodeRepresentation nodeRep, final byte[] file) {
        final Node node = nodeRepresentationToNode(nodeRep);
        // TODO support multiple buckets
        node.setBucket(StorageService.ROOT_BUCKET);

        try {
            final Property originalFileNameProperty = propertyService.createProperty(qnameService.stringToQName(ContentModel.PROP_NAME), nodeRep.getProperties().get(ContentModel.PROP_NAME));

            node.getProperties().add(originalFileNameProperty);
        } catch (final ParseException e) {
            log.error(e.getMessage(), e);
        }
        nodeDAO.save(node);

        nodeRep.setUuid(node.getUuid());
        nodeRep.setBucket(node.getBucket());
        nodeRep.setType(typeService.typeToString(node.getType()));

        storageService.uploadFile(nodeRep, file);

        log.debug("File uploaded: {}", nodeRep);

        final NodeIndex nodeIndex = nodeMapper.nodeToNodeIndex(node);
        nodeIndexerService.createNodeIndex(nodeIndex);

        return nodeRep;
    }

    public String getOriginalFileName(final Node node) {

        if (!node.getType().equals(typeService.stringToType(ContentModel.TYPE_CONTENT))) {
            throw new IllegalArgumentException(
                    "Node must be of type: " + ContentModel.TYPE_CONTENT + ". Actual type: " + typeService.typeToString(node.getType()));
        }

        if (node.getProperties() != null) {
            final List<Property> res =
                    node.getProperties().stream().filter(i -> qnameService.qnameToString(i.getQname()).equals(ContentModel.PROP_NAME)).toList();

            if (!res.isEmpty()) {
                return res.get(0).getStringValue();
            }
        }

        throw new IllegalArgumentException("Node does not contain the property: " + ContentModel.PROP_NAME + ". Actual properties: " + node.getProperties());
    }


}
