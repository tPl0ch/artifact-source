package com.atomist.source.file

import java.io.{File, FileInputStream}
import java.nio.file.Files

import com.atomist.source._
import com.atomist.util.BinaryDecider.isBinaryContent
import org.scalatest._

object FileSystemArtifactSourceTest {

  val AtomistTemplatesSource = ClassPathArtifactSource.toArtifactSource("spring-boot")

  def ignoreFiles1ZipId = {
    val f = ClassPathArtifactSource.classPathResourceToFile("ignore-files-1.zip")
    ZipFileInput(new FileInputStream(f))
  }

  def ignoreFiles2ZipId = {
    val f = ClassPathArtifactSource.classPathResourceToFile("ignore-files-2.zip")
    ZipFileInput(new FileInputStream(f))
  }
}

class FileSystemArtifactSourceTest extends FlatSpec with Matchers {

  import FileSystemArtifactSourceTest._

  val fWriter = new FileSystemArtifactSourceWriter

  it should "handle classpath directory not found" in {
    an[ArtifactSourceException] should be thrownBy (ClassPathArtifactSource toArtifactSource "this is complete nonsense")
  }

  it should "find single file and verify contents" in {
    val classpathSource = ClassPathArtifactSource.toArtifactSource("java-source/HelloWorldService.java")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(a => a.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    aFile.content should have size aFile.contentLength
    isBinaryContent(aFile.content) shouldBe false
  }

  it should "find single image file" in {
    val classpathSource = ClassPathArtifactSource.toArtifactSource("spring-boot/web-template/src/main/resources/atomist-logo-horiz.png")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(a => a.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    isBinaryContent(aFile.content) shouldBe true
  }

  it should "find single binary file" in {
    val classpathSource = ClassPathArtifactSource.toArtifactSource("binary.dat")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(a => a.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    isBinaryContent(aFile.content) shouldBe true
  }

  it should "find single binary executable file" in {
    val classpathSource = ClassPathArtifactSource.toArtifactSource("binary-executable.dat")
    val artifacts = classpathSource.artifacts
    val files = artifacts.filter(a => a.isInstanceOf[FileArtifact])
    artifacts.size should be > 0
    val aFile = files.head.asInstanceOf[FileArtifact]
    aFile.contentLength should be > 0
    isBinaryContent(aFile.content) shouldBe true
    // aFile.mode should be(493)
  }

  it should "find directory" in {
    val artifacts = AtomistTemplatesSource.artifacts
    artifacts.exists(f => f.name contains ".vm")
  }

  it should "find all files via flatten" in {
    validateTargetDirectory(AtomistTemplatesSource)
  }

  // TODO some of these tests are more generic ArtifactSource tests
  it should "be able to cache" in {
    val classpathSource = AtomistTemplatesSource
    classpathSource.allFiles.exists(f => f.isCached) shouldBe false
    validateTargetDirectory(classpathSource)
    val cachedCopy = classpathSource.cached
    cachedCopy.allFiles.exists(f => !f.isCached) shouldBe false
    validateTargetDirectory(cachedCopy)
  }

  it should "be able to filter files" in {
    val s = AtomistTemplatesSource / "atomistTemplates"
    val files = s.allFiles
    files.exists(f => f.name contains ".vm") shouldBe true
    val filtered = s.filter(d => true, f => !f.name.contains(".vm"))
    filtered.allFiles.exists(f => f.name contains ".vm") shouldBe false
    withClue("should leave nothing after filter") {
      filtered.allFiles.isEmpty shouldBe true
    }
  }

  it should "be able to filter directories" in {
    val s = AtomistTemplatesSource
    s.allFiles.exists(f => f.name contains "Application") shouldBe true
    val filtered = s.filter(d => !d.name.contains("spring"), f => true)
    filtered.allFiles.exists(f => f.name contains "Java") shouldBe false
  }

  it should "be able to find existing directory" in {
    val s = AtomistTemplatesSource
    s.directories.nonEmpty shouldBe true
    s.findDirectory("atomistTemplates").isDefined shouldBe true
  }

  it should "not be able to find bogus directory" in {
    val s = AtomistTemplatesSource
    s.directories.nonEmpty shouldBe true
    s.findDirectory("xsdfsdfsdfsdf").isDefined shouldBe false
  }

  it should "reject bogus file rootPath" in {
    val f: File = new File("/this/is/not/a/real.rootPath")
    val fsid = FileSystemArtifactSourceIdentifier(f)
    an[ArtifactSourceException] should be thrownBy new FileSystemArtifactSource(fsid)
  }

  it should "handle ignoring files for first test source" in {
    val zid = ignoreFiles1ZipId
    val zipSource = ZipFileArtifactSourceReader.fromZipSource(zid)

    val tmpDir = Files.createTempDirectory(null)
    val fid = FileSystemArtifactSourceIdentifier(tmpDir.toFile)
    val file = fWriter.write(zipSource, fid, SimpleSourceUpdateInfo(getClass.getName))

    val as = new FileSystemArtifactSource(fid)
    as.findDirectory(".atomist/node_modules") shouldBe defined
  }

  it should "handle ignoring files for second test source" in {
    val zid = ignoreFiles2ZipId
    val zipSource = ZipFileArtifactSourceReader.fromZipSource(zid)

    val tmpDir = Files.createTempDirectory(null)
    val fid = FileSystemArtifactSourceIdentifier(tmpDir.toFile)
    fWriter.write(zipSource, fid, SimpleSourceUpdateInfo(getClass.getName))

    val as = new FileSystemArtifactSource(fid)
    as.findDirectory(".atomist/node_modules") shouldBe defined
  }

  private def validateTargetDirectory(s: ArtifactSource): Unit = {
    val files = s.allFiles
    files.exists(f => f.name contains ".vm")
  }
}
