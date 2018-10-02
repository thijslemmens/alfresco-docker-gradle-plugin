package eu.xenit.gradle.docker;

import com.avast.gradle.dockercompose.ComposeExtension;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import com.bmuschko.gradle.docker.tasks.image.DockerTagImage;
import com.bmuschko.gradle.docker.tasks.image.Dockerfile;
import eu.xenit.gradle.JenkinsUtil;
import eu.xenit.gradle.docker.tasks.internal.DockerBuildImage;
import eu.xenit.gradle.git.CannotConvertToUrlException;
import eu.xenit.gradle.git.GitInfoProvider;
import java.util.function.BiConsumer;
import org.codehaus.groovy.runtime.MethodClosure;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static eu.xenit.gradle.git.JGitInfoProvider.GetProviderForProject;

/**
 * Created by thijs on 10/25/16.
 */
public class DockerBuildBehavior {

    private Supplier<DockerBuildExtension> dockerBuildExtension;
    private Supplier<File> dockerFile;
    private Dockerfile dockerfileCreator;

    public DockerBuildBehavior(Supplier<DockerBuildExtension> dockerBuildExtension, Supplier<File> dockerFile) {
        this.dockerBuildExtension = dockerBuildExtension;
        this.dockerFile = dockerFile;
    }

    public DockerBuildBehavior(Supplier<DockerBuildExtension> dockerBuildExtension, Dockerfile dockerfileCreator) {
        this.dockerBuildExtension = dockerBuildExtension;
        this.dockerfileCreator = dockerfileCreator;
    }

    public void apply(Project project) {
        this.execute(project);
        //project.afterEvaluate(this::execute);
    }

    /**
     * Configures a task based on the outputs from an other task that are not yet available at configuration time
     * @param dependentTask The task to configure
     * @param sourceTask The task from which configuration is derived
     * @param configurator Function that is called to configure the task
     */
    private <T extends Task, U extends Task> void configureFromTask(T dependentTask, U sourceTask, BiConsumer<T, U> configurator) {
        dependentTask.dependsOn(sourceTask);
        sourceTask.doLast("Configure "+dependentTask.getName(), task -> {
            configurator.accept(dependentTask, sourceTask);
        });
    }

    public void execute(Project project) {
        Path buildPath = project.getBuildDir().toPath();

        DockerBuildImage buildDockerImage = createDockerBuildImageTask(project,"buildDockerImage", dockerBuildExtension);
        buildDockerImage.setDescription("Build the docker image");
        if (dockerfileCreator != null) {
            buildDockerImage.setDockerFile(dockerfileCreator::getDestFile);
            buildDockerImage.setInputDir(() -> dockerfileCreator.getDestFile().getParentFile());
            buildDockerImage.dependsOn(dockerfileCreator);
        }


        if(dockerFile != null) {
            buildDockerImage.setDockerFile(this.dockerFile);
            buildDockerImage.setInputDir(() -> this.dockerFile.get().getParentFile());
        }

        Supplier<String> incrementalImageIdSupplier = buildDockerImage::getImageId;
        Dockerfile labelDockerFile = labelDockerFile(project, buildPath, incrementalImageIdSupplier);
        labelDockerFile.dependsOn(buildDockerImage);
        DockerBuildImage buildLabels = createDockerBuildImageTask(project,"buildLabels", dockerBuildExtension);
        buildLabels.setDockerFile(labelDockerFile::getDestFile);
        buildLabels.setInputDir(() -> labelDockerFile.getDestFile().getParentFile());
        buildLabels.dependsOn(labelDockerFile);

        // disable pull-image, because the image we are going to label,
        // was just built here and will not be available in the remote repository
        project.afterEvaluate((project1) -> {
            buildLabels.setPull(false);
        });
        buildLabels.setDescription("Build the docker image with extra labels to make it easier to identify the image");
        buildDockerImage.finalizedBy(buildLabels);
        buildLabels.doLast(task -> {
                ComposeExtension composeExtension = (ComposeExtension) project.getExtensions().getByName("dockerCompose");
                composeExtension.getEnvironment().put("DOCKER_IMAGE", buildLabels.getImageId());
        });

        DefaultTask dockerPushImage = project.getTasks().create("pushDockerImage", DefaultTask.class);
        dockerPushImage.setGroup("Docker");
        dockerPushImage.setDescription("Collection of all the pushTags");
        dockerPushImage.dependsOn(buildLabels);

        project.afterEvaluate((project1 -> {
            List<DockerTagImage> dockerTagImages = tagDockerImage(project, buildLabels);
            List<DockerPushImage> pushTags = getPushTags(project, dockerTagImages);
            dockerPushImage.dependsOn(pushTags);
        }));

        Task task = project.getTasks().getAt("composeUp");
        task.dependsOn(buildLabels);
    }

    private DockerBuildImage createDockerBuildImageTask(Project project, String name, Supplier<DockerBuildExtension> dockerBuildExtension) {
        DockerBuildImage dockerBuildImage;
        if(project.getTasks().findByPath(name) != null){
            dockerBuildImage = (DockerBuildImage) project.getTasks().getAt(name);
        } else {
            dockerBuildImage = project.getTasks().create(name, DockerBuildImage.class);
        }
        //incrementalDockerImage.getOutputs().upToDateWhen(task -> true);

        project.afterEvaluate((project1) -> {
            DockerBuildExtension extension = dockerBuildExtension.get();
            dockerBuildImage.setPull(extension.getPull());
            dockerBuildImage.setNoCache(extension.getNoCache());
            dockerBuildImage.setRemove(extension.getRemove());
        });

        return dockerBuildImage;
    }

    private Dockerfile labelDockerFile(Project project, Path buildPath, Supplier<String> imageIdSupplier) {
        List<Consumer<Dockerfile>> instructions = new ArrayList<>();
        Dockerfile labelDockerFile = project.getTasks().create("labelDockerFile", Dockerfile.class);
        labelDockerFile.setDestFile(buildPath.resolve(Paths.get("labelDocker", "Dockerfile")).toFile());
        labelDockerFile.from(new MethodClosure(imageIdSupplier, "get"));

        Map<String, String> labels = new HashMap<>();
        GitInfoProvider gitInfoProvider = GetProviderForProject(project);
        String labelPrefix = "eu.xenit.gradle-plugin.git.";
        if(gitInfoProvider != null) {
            if(gitInfoProvider.getOrigin() != null) {
                labels.put(labelPrefix+"origin", gitInfoProvider.getOrigin());
                try {
                    labels.put(labelPrefix+"commit.url", gitInfoProvider.getCommitURL().toExternalForm());
                } catch (CannotConvertToUrlException e) {
                    e.printStackTrace();
                }
            }
            labels.put(labelPrefix+"branch", gitInfoProvider.getBranch());
            labels.put(labelPrefix+"commit.id", gitInfoProvider.getCommitChecksum());
            labels.put(labelPrefix+"commit.author", gitInfoProvider.getCommitAuthor());
            labels.put(labelPrefix+"commit.message", '"'+gitInfoProvider.getCommitMessage()
                    .replaceAll("\"", "\\\\\"")
                    .replaceAll("(\r)*\n", "\\\\\n")+'"');
        }
        if(!labels.isEmpty()) {
            instructions.add(task -> task.label(labels));
        }
        instructions.forEach(instruction -> instruction.accept(labelDockerFile));
        return labelDockerFile;
    }

    private List<DockerTagImage> tagDockerImage(Project project, DockerBuildImage dockerBuildImage) {
        List<DockerTagImage> tagTasks = new ArrayList<>();
        List<String> tags = dockerBuildExtension.get().getTags();

        if(dockerBuildExtension.get().getAutomaticTags()) {
            tags = tags.stream().map(tag -> {
                if (isMaster()) {
                    return tag;
                } else {
                    return JenkinsUtil.getBranch() + "-" + tag;
                }
            }).collect(Collectors.toList());

            if (JenkinsUtil.getBuildId() != null) {
                if (isMaster()) {
                    tags.add("build-" + JenkinsUtil.getBuildId());
                } else {
                    tags.add(JenkinsUtil.getBranch() + "-build-" + JenkinsUtil.getBuildId());
                }
            }

            if (isMaster()) {
                tags.add("latest");
            } else {
                tags.add(JenkinsUtil.getBranch());
            }
        }

        for (String tag : tags) {
            DockerTagImage dockerTagImage = project.getTasks().create("tagImage" + tag, DockerTagImage.class);
            configureFromTask(dockerTagImage, dockerBuildImage, (tagTask, buildTask) -> {
                tagTask.setImageId(buildTask.getImageId());
            });
            dockerTagImage.setTag(tag);
            dockerTagImage.setDescription("Tag docker image with tag "+tag);
            String dockerRepo = getDockerRepository();
            dockerTagImage.setRepository(dockerRepo);
            tagTasks.add(dockerTagImage);
        }
        return tagTasks;
    }

    private boolean isMaster() {
        return "master".equals(JenkinsUtil.getBranch());
    }

    private List<DockerPushImage> getPushTags(Project project, List<DockerTagImage> dockerTagImages){
        List<DockerPushImage> result = new ArrayList<>();
        for (DockerTagImage dockerTagImage: dockerTagImages) {
            DockerPushImage pushTag = project.getTasks().create("pushTag"+dockerTagImage.getTag(), DockerPushImage.class);
            pushTag.setImageName(dockerTagImage.getRepository()+":"+dockerTagImage.getTag());
            pushTag.dependsOn(dockerTagImage);
            pushTag.setDescription("Push image with tag "+dockerTagImage.getTag());
            result.add(pushTag);
        }
        return result;
    }

    private String getDockerRepository() {
        return dockerBuildExtension.get().getRepository();
    }

}
