package com.atomist.source.git.github

import java.io.FileInputStream
import java.nio.file.Paths

import com.atomist.source.file._
import com.atomist.source.git.TestConstants.Token
import com.atomist.source.git.{FileSystemGitArtifactSource, GitRepositoryCloner}
import com.atomist.source.{ArtifactSourceTest, SimpleCloudRepoId}
import com.atomist.util.BinaryDecider
import org.apache.commons.io.IOUtils
import resource._

class GitHubArtifactSourceWriterTest extends GitHubMutatorTest(Token) {

  private val gitHubWriter = GitHubArtifactSourceWriter(Token)

  def springBootZipFileId: ZipFileInput = {
    val f = ClassPathArtifactSource.classPathResourceToFile("springboot1.zip")
    ZipFileInput(new FileInputStream(f))
  }

  "GitHubArtifactSourceWriter" should "create repository and copy contents in root directory only" in {
    val newTempRepo = newTemporaryRepo()

    val helloWorldProject = ClassPathArtifactSource.toArtifactSource("java-source/HelloWorldService.java")
    val cri = SimpleCloudRepoId(newTempRepo.name, newTempRepo.ownerName)
    val ghid = GitHubArtifactSourceLocator(cri)
    val fa = gitHubWriter.write(helloWorldProject, GitHubSourceUpdateInfo(ghid, getClass.getName))
    fa.size shouldEqual 1
    fa.head.uniqueId shouldBe defined

    Thread sleep 1000
    val read = TreeGitHubArtifactSource(GitHubArtifactSourceLocator(cri), ghs)
    ArtifactSourceTest.validateCopy(helloWorldProject, read)
  }

  it should "create repository and write contents in many directories" in {
    val newTempRepo = newPopulatedTemporaryRepo()

    val springBootProject = ZipFileArtifactSourceReader fromZipSource springBootZipFileId
    val cri = SimpleCloudRepoId(newTempRepo.name, newTempRepo.ownerName)
    val ghid = GitHubArtifactSourceLocator(cri)
    val artifacts = gitHubWriter.write(springBootProject, GitHubSourceUpdateInfo(ghid, getClass.getName))
    artifacts.size should be > 1
    val read = TreeGitHubArtifactSource(GitHubArtifactSourceLocator(cri), ghs)
    ArtifactSourceTest.validateCopyAllowingExtras(springBootProject, read)
  }

  it should "clone a remote repository, push contents to a new repository, and verify contents" in {
    val grc = GitRepositoryCloner(Token)
    val cloned = grc.clone("spring-rest-seed", "atomist-seeds") match {
      case Some(file) => file
      case None => fail
    }
    val repo = "atomist-seeds/spring-rest-seed"
    val id = NamedFileSystemArtifactSourceIdentifier(repo, cloned)
    val as = FileSystemGitArtifactSource(id)

    val newTempRepo = newPopulatedTemporaryRepo()
    val cri = SimpleCloudRepoId(newTempRepo.name, newTempRepo.ownerName)
    gitHubWriter.write(as, GitHubSourceUpdateInfo(GitHubArtifactSourceLocator(cri), "new project from seed"))

    val clonedSeed = grc.clone(cri.repo, cri.owner) match {
      case Some(file) => file
      case None => fail
    }
    val clonedAs = FileSystemGitArtifactSource(NamedFileSystemArtifactSourceIdentifier(repo, clonedSeed))
    val cmdFile = clonedAs.findFile("mvnw.cmd")
    cmdFile shouldBe defined
    BinaryDecider.isBinaryContent(cmdFile.get.content) shouldBe false

    val jar = clonedAs.findFile(".mvn/wrapper/maven-wrapper.jar")
    jar shouldBe defined
    val jarFile = jar.get
    val content = managed(jarFile.inputStream()).acquireAndGet(IOUtils.toByteArray)
    BinaryDecider.isBinaryContent(content) shouldBe true
    val jid = ZipFileInput(Paths.get(clonedAs.id.rootFile.getPath, jarFile.path).toFile)
    val jarSource = ZipFileArtifactSourceReader.fromZipSource(jid)
    jarSource.findFile("META-INF/MANIFEST.MF") shouldBe defined
  }
}