package eu.xenit.gradle.docker;

import groovy.lang.GString;
import org.gradle.api.Project;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by thijs on 10/25/16.
 */
public class DockerBuildExtension {

    private String repository;

    public DockerBuildExtension(Project project) {
        repository = project.getName();
    }


    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    /**
     * @deprecated As of release 2.2.0, replaced by {@link #setRepository(String)}
     * @param repository
     */
    @Deprecated
    public void setRepositoryBase(String repository){
        setRepository(repository);
    }

    private List<String> tags = new ArrayList<>();

    public List<String> getTags() {
        return tags;
    }


    public void setTags(List<Object> tags) {
        List<String> converted = new ArrayList<String>();
        for (int i = 0; i < tags.size(); i++) {
            Object tag = tags.get(i);
            if(!(tag instanceof String) && !(tag instanceof GString)){
                throw new Error("Only strings and gstrings are supported.");
            }
            converted.add(tag.toString());
        }
        this.tags = converted;
    }
    /**
     * Do not modify tags before tagging the docker image
     */
    private boolean automaticTags = true;

    /**
     * Used to trigger a warning when no explicit value is set for automaticTags
     */
    private boolean _automaticTagsExplicitlySet = false;

    public boolean getAutomaticTags() {
        if(!_automaticTagsExplicitlySet) {
            LoggerFactory.getLogger(getClass())
                    .warn("[eu.xenit.docker] automaticTags currently defaults to true, but it will change to false in version 5.0.0."
                            + "If you want to continue using the current behavior, explicitly set automaticTags now to avoid surprises in the future.");
        }
        return automaticTags;
    }

    public void setAutomaticTags(boolean automaticTags) {
        _automaticTagsExplicitlySet = true;
        this.automaticTags = automaticTags;
    }


    /**
     * Always attempt to pull a newer version of the image (default false)
     */
    private boolean pull;

    public boolean getPull() {
        return pull;
    }

    public void setPull(boolean pull) {
        this.pull = pull;
    }

    /**
     * Do not use cache when building the image (default false)
     */
    private boolean noCache;

    public boolean getNoCache() {
        return noCache;
    }

    public void setNoCache(boolean noCache) {
        this.noCache = noCache;
    }

    /**
     * Remove intermediate containers after a successful build (default true)
     */
    private boolean remove = true;

    public boolean getRemove() {
        return remove;
    }

    public void setRemove(boolean remove) {
        this.remove = remove;
    }
}
