package com.trodix.documentstorage.persistance.dao;

import java.util.List;

import com.trodix.documentstorage.model.ContentModel;
import com.trodix.documentstorage.persistance.mapper.StoredFileRowMapper;
import com.trodix.documentstorage.persistance.utils.DaoUtils;
import org.springframework.jdbc.core.RowCountCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.trodix.documentstorage.persistance.entity.Aspect;
import com.trodix.documentstorage.persistance.entity.Node;
import com.trodix.documentstorage.persistance.entity.Property;
import com.trodix.documentstorage.persistance.entity.Type;
import com.trodix.documentstorage.persistance.repository.NodeRepository;
import lombok.AllArgsConstructor;

@Repository
@Transactional
@AllArgsConstructor
public class NodeDAO {

    private final NodeRepository nodeRepository;

    private final NamedParameterJdbcTemplate tpl;

    private final TypeDAO typeDAO;

    private final AspectDAO aspectDAO;

    private final PropertyDAO propertyDAO;

    private final StoredFileDAO storedFileDAO;

    public Node save(Node node) {

        final KeyHolder keyHolder = new GeneratedKeyHolder();

        // save type relation
        if (node.getType().getId() == null) {
            final Type type = typeDAO.save(node.getType());
            node.getType().setId(type.getId());
        }

        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("bucket", node.getBucket());
        params.addValue("directory_path", node.getDirectoryPath());
        params.addValue("uuid", node.getUuid());
        params.addValue("type_id", node.getType().getId());
        params.addValue("versions", node.getVersions()); // default to 0 (before the file is uploaded

        if (node.getDbId() == null) {
            final String query = "INSERT INTO node (bucket, directory_path, uuid, type_id, versions) VALUES (:bucket, :directory_path, :uuid, :type_id, :versions)";
            tpl.update(query, params, keyHolder);

            node.setDbId((Long) keyHolder.getKeys().get("id"));
        } else {
            params.addValue("id", node.getDbId());
            final String query = "UPDATE node SET bucket = :bucket, directory_path = :directory_path, uuid = :uuid, type_id = :type_id, versions = :versions WHERE id = :id";
            tpl.update(query, params);
        }

        int maxVersion = storedFileDAO.findStoredFileLatestVersion(node.getDbId());

        if (maxVersion > node.getVersions()) {
            node.setVersions(maxVersion);
            save(node);
        }

        node = persistNodeAspects(node);
        node = persistNodeProperties(node);

        return node;
    }

    public List<Node> findByPath(final String path) {
        return nodeRepository.findByDirectoryPath(path);
    }

    public boolean isDirectoryAtPathExists(final String directoryPath) {

        String directoryParentPath = directoryPath.substring(0, directoryPath.lastIndexOf("/"));

        if (directoryParentPath.length() == 0) {
            directoryParentPath = "/";
        }

        String directoryName = directoryPath.substring(directoryPath.lastIndexOf("/") + 1, directoryPath.length());

        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("directory_parent_path", directoryParentPath);
        params.addValue("directory_name", directoryName);
        params.addValue("content_model_namespace", ContentModel.TYPE_DIRECTORY.split(":")[0]);
        params.addValue("type_directory_name", ContentModel.TYPE_DIRECTORY.split(":")[1]);
        params.addValue("qname_content_model_name", ContentModel.PROP_NAME.split(":")[1]);

        final String query = """
                SELECT count(n.id) FROM node n
                INNER JOIN type t ON t.id = n.type_id
                INNER JOIN qname q ON q.id = t.qname_id
                INNER JOIN namespace n2 ON q.namespace_id  = n2.id
                INNER JOIN node_property np ON np.node_id = n.id
                INNER JOIN property p ON p.id = np.properties_id
                WHERE n2.name = :content_model_namespace AND q.name = :type_directory_name and p.qname_id = (
                    SELECT q2.id FROM qname q2
                    INNER JOIN namespace n3 ON n3.id = q2.namespace_id
                    WHERE n3.name = :content_model_namespace AND q2.name = :qname_content_model_name
                )
                AND n.directory_path = :directory_parent_path
                AND p.string_value = :directory_name
                """;

        return tpl.queryForObject(query, params, Integer.class) > 0;
    }

    public Node findByUuId(final String uuid) {
        return nodeRepository.findByUuid(uuid).orElse(null);
    }

    protected Node persistNodeAspects(final Node node) {
        // persist aspects
        List.copyOf(node.getAspects()).forEach(aspect -> {
            final Aspect savedAspect = aspectDAO.save(aspect);
            node.getAspects().replaceAll(a -> a.getQname().equals(aspect.getQname()) ? savedAspect : a);
        });

        persistNodeAspectRelation(node);

        return node;
    }

    protected void persistNodeAspectRelation(final Node node) {

        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("node_id", node.getDbId());

        node.getAspects().forEach(aspect -> {

            params.addValue("aspects_id", aspect.getId());

            final String existRelationQuery = """
                    SELECT na.node_id as na_node_id, na.aspects_id as na_aspects_id FROM node_aspect na
                        WHERE na.node_id = :node_id AND na.aspects_id = :aspects_id""";

            final RowCountCallbackHandler rowCount = new RowCountCallbackHandler();
            tpl.query(existRelationQuery, params, rowCount);

            if (rowCount.getRowCount() == 0) {
                final String query = "INSERT INTO node_aspect (node_id, aspects_id) VALUES (:node_id, :aspects_id)";
                tpl.update(query, params);
            }
        });
    }

    protected Node persistNodeProperties(final Node node) {
        // persist properties
        List.copyOf(node.getProperties()).forEach(property -> {
            final Property savedProperty = propertyDAO.save(property);
            node.getProperties().replaceAll(p -> p.getQname().equals(property.getQname()) ? savedProperty : p);
        });

        persistNodePropertyRelation(node);

        return node;
    }

    protected void persistNodePropertyRelation(final Node node) {

        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("node_id", node.getDbId());

        node.getProperties().forEach(property -> {

            params.addValue("properties_id", property.getId());

            final String existRelationQuery = """
                    SELECT np.node_id as np_node_id, np.properties_id as np_properties_id FROM node_property np
                        WHERE np.node_id = :node_id AND np.properties_id = :properties_id""";

            final RowCountCallbackHandler rowCount = new RowCountCallbackHandler();
            tpl.query(existRelationQuery, params, rowCount);

            if (rowCount.getRowCount() == 0) {
                final String query = "INSERT INTO node_property (node_id, properties_id) VALUES (:node_id, :properties_id)";
                tpl.update(query, params);
            }
        });

    }

}
