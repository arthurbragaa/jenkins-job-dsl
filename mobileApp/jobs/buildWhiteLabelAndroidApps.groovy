package mobile

import org.yaml.snakeyaml.Yaml
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_LOG_ROTATOR

/**
 buildWhiteLabelAndroidApps.groovy

 This dsl-script creates Jenkins jobs to perform the following:
   - Build an apk based on a user-supplied git-hash from a given edx android repository
   - Archive the apk file on Jenkins for history & quick testing
   - Publish the apk to HockeyApp (a service for hosting & disrtibuting application files)
   - TODO: Run tests on the generated apk file (linting, unit tests, screenshot tests)
 Depending on the "release" flag mentioned below, the job will be considered either 'release' or 'debug'
 Release builds will also use special gradle.properties and keystore files when compiled, to sign
 the build.

 ==========================================================================================
 In order to run, the job requires the following be loaded into Jenkins as secrets files:
   - A secret yml file: This contains a number of jobConfigs, one for each desired Jenkins job
   - A gradle.properties file (release builds only): A key-value file used in the gradle build process **
       For more informataion, see https://github.com/edx/edx-app-android/blob/master/README.rst#building-for-release
   - A key store file (release builds only): These are used to sign Android applications ***
   ** Debug builds require less information in the gradle.properties file than release builds, and are 
      created by the build scripts
   *** Debug builds will use default signing keys from the Android SDK

 Example secret YAML file used by this script
 jobConfig:
    jobName: build-demo-android-app => name of generated Jenkins job
    public: true => (boolean) if the job should be open or not
    release: false => (boolean) whether or not the job will build a release apk (as opposed to a debuggable apk)
    buildScript: build.sh => script in appBaseDir to run in order to compile the apk
    gradleProperties: GRADLE_PROPERTIES => env var exposed as secret which points to gradle.properties for this build
    keystore: APP_KEYSTORE => env var exposed as secret which points to the keystore file to use in signing
    keystoreFile: APP_KEYSTORE_FILE => output file to copy the keystore into
    gitRepo: https://github.com/org/project.git => source repo for the app to build
    gitCredential: git_user => credentials for git user
    appBaseDir: android-app-project => name of directory to clone app source into
    appBaseName: edx-demo-app => first part of desired apk name produced by this job. Apk names should have the following
                 naming structure: '$appBaseName-$gitHash.apk'
    generatedApkName: edx-demo-app.apk => original name of the apk built via gradle (before our renaming scheme)
    sshAgent: ssh_user => reference to ssh user for using git submodules from within shell steps in Jenkins jobs
    hockeyAppApiToken: abc123 => token used to access the hockey app api for publishing apks
**/

/* stdout logger */
/* use this instead of println, because you can pass it into closures or other scripts. */
Map config = [:]
Binding bindings = getBinding()
config.putAll(bindings.getVariables())
PrintStream out = config['out']

out.println('Parsing secret YAML file')
try {
    /* Parse k:v pairs from the secret file referenced by secretFileVariable */
    String contents = new File("${BUILD_ANDROID_SECRET}").text
    Yaml yaml = new Yaml()
    secretMap = yaml.load(contents)
}
catch (any) {
    out.println('Jenkins DSL: Error parsing secret YAML file')
    out.println('Exiting with error code 1')
    return 1
}

out.println('Successfully parsed secret YAML file')

// Iterate over the configurations in BUILD_ANDROID_SECRET
secretMap.each { jobConfigs ->

    Map jobConfig = jobConfigs.getValue()

    // Test parsed secret contains the correct fields
    assert jobConfig.containsKey('jobName')
    assert jobConfig.containsKey('public')
    assert jobConfig.containsKey('release')
    assert jobConfig.containsKey('buildScript')
    assert jobConfig.containsKey('gradleProperties')
    assert jobConfig.containsKey('keystore')
    assert jobConfig.containsKey('keystoreFile')
    assert jobConfig.containsKey('gitRepo')
    assert jobConfig.containsKey('gitCredential')
    assert jobConfig.containsKey('appBaseDir')
    assert jobConfig.containsKey('appBaseName')
    assert jobConfig.containsKey('generatedApkName')
    assert jobConfig.containsKey('sshAgent')
    assert jobConfig.containsKey('hockeyAppApiToken')

    // Create a Jenkins job
    job(jobConfig['jobName']) {

        // This is a private job
        if (!jobConfig['public']) {
            authorization {
                blocksInheritance(true)
                permissionAll('edx')
            }
        }

        description('Compile, sign, test and publish the edX Android app to HockeyApp')

        // Clean up older builds/logs
        logRotator JENKINS_PUBLIC_LOG_ROTATOR()
        
        concurrentBuild(false)
        // Run this build on the Jenkins worker configured with the Android SDK
        label('android-worker')

        // Parameterize build with:
        //"HASH", the git hash used to pull the app source code
        //"RELEASE_NOTES", a string (multi-line) for annotating the builds published to hockeyApp
        parameters {
            stringParam('HASH', 'refs/heads/master', 'Git hash of the project to build. Default = most recent hash of master.')
            textParam('RELEASE_NOTES', 'Built with Jenkins', 'Plain text release notes. Add content here and it will be published to HockeyApp along with the app.')
        }

        scm {
            git { 
                remote {
                    url(jobConfig['gitRepo'])
                    if (!jobConfig['public'].toBoolean()) {
                        credentials(jobConfig['gitCredential'])
                    }
                }
                branch('\${HASH}')
                browser()
                extensions {
                    cleanBeforeCheckout()
                    relativeTargetDirectory(jobConfig['appBaseDir'])
                }
            }
        }

        wrappers {
            timeout {
                absolute(20)
                abortBuild()
            }
            timestamps()
            colorizeOutput('xterm')
            // Recursively use ssh while initializing git submodules
            sshAgent(jobConfig['sshAgent'])
            // Grant release builds access to secrets
            if (jobConfig['release']) {
                credentialsBinding {
                    file('KEY_STORE', jobConfig['keystore'])
                    file('GRADLE_PROPERTIES', jobConfig['gradleProperties'])
                }
            }
        }

        // Inject environment variables to make the build script generic
        environmentVariables {
            env('ANDROID_HOME', '/opt/Android/android-sdk-linux')
            env('BUILD_SCRIPT', jobConfig['buildScript'])
            env('APP_BASE_DIR', jobConfig['appBaseDir'])
            env('APP_BASE_NAME', jobConfig['appBaseName'])
            env('GEN_APK', jobConfig['generatedApkName'])
            env('KEY_STORE_FILE', jobConfig['keystoreFile'])
        }

        steps {
            // Run the scripts to build and rename the app
            def copyKeyScript = readFileFromWorkspace('mobileApp/resources/copy_keys.sh')
            def buildScript = readFileFromWorkspace('mobileApp/resources/organize_android_app_build.sh')
            def testSigningScript = readFileFromWorkspace('mobileApp/resources/check_release_build.sh')
            def prepareEmulatorScript = readFileFromWorkspace('mobileApp/resources/prepare_emulator.sh')

            // Copy keystores into workspace for release builds
            if (jobConfig['release']) {
                shell(copyKeyScript)
            }
            shell(buildScript)
            // Verify that they are signed properly for release builds
            if (jobConfig['release']) {
                shell(testSigningScript)
            }

            // Run the gradle targets to perform linting and unit tests
            gradle{
                useWrapper(true)
                makeExecutable(true)
                fromRootBuildScriptDir(true)
                tasks('lintProdDebug')
                tasks('copyLinBuildArtifacts')
                tasks('testProdDebugUnitTestCoverage')
                tasks('copyUnitTestBuildArtifacts')
                rootBuildScriptDir("\${APP_BASE_DIR}/edx-app-android/")
            }

            // Set up the Android Emulator (AVD) for testing
            shell(prepareEmulatorScript)

            // Run the gradle targets to perform screenshot tests on an AVD
            gradle{
                useWrapper(true)
                makeExecutable(true)
                fromRootBuildScriptDir(true)
                tasks('verifyMode')
                tasks('screenshotTests')
                switches('-PdisablePreDex')
                rootBuildScriptDir("\${APP_BASE_DIR}/edx-app-android/")
            }

        }

        publishers {
            // Archive the artifacts, in this case, the APK file:
            archiveArtifacts {
                allowEmpty(false)
                def screenshotPath = "${jobConfig['appBaseDir']}/edx-app-android/VideoLocker/screenshots/*png"
                pattern("artifacts/\$APP_BASE_NAME*, ${screenshotPath}")
            }

            publishHtml {
                report("${APP_BASE_DIR}/edx-app-android/VideoLocker/build/reports/tests/prodDebug/") {
                    allowMissing('')
                    reportFiles('')
                    reportName('')
                }
            }

            // Publish the application to HockeyApp
            configure { project ->
                project / publishers << 'hockeyapp.HockeyappRecorder' (schemaVersion: '2') {
                    applications {
                        'hockeyapp.HockeyappApplication' (plugin: 'hockeyapp@1.2.1', schemaVersion: '1') {
                            apiToken jobConfig['hockeyAppApiToken']
                            notifyTeam true
                            filePath 'artifacts/*.apk'
                            dowloadAllowed true
                            releaseNotesMethod (class: "net.hockeyapp.jenkins.releaseNotes.ManualReleaseNotes") {
                                releaseNotes "\${RELEASE_NOTES}"
                                isMarkDown false
                            }
                            uploadMethod (class: 'net.hockeyapp.jenkins.uploadMethod.AppCreation') {}
                        }
                    }
                }
            }
        }
    }
}
