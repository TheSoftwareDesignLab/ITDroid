# ITDroid

ITDroid is a testing framework for Android applications oriented to Internationalization.


## Compile
Download and compile MutAPK with the following commands:
```
git clone https://github.com/TheSoftwareDesignLab/ITDroid.git
cd ITDroid
mvn clean
mvn package
```
The generated runnable jar can be found in: ``ITDroid/target/ITDroid-0.0.1.jar``

## Usage
To run ITDroid use the following command, specifying the required arguments:
```
java -jar ITDroid-0.0.1.jar <APKPath> <AppPackage> <ExtraComponentFolder> <settingsDir> <alpha> <Output>
```
### Arguments
Provide the following list of required arguments when running MutAPK:
1. ``APKPath``: relative path of the apk to mutate;
2. ``AppPackage``: App main package name;
3. ``ExtraCompFolder``:  relative path of the extra component folder (``ITDroid/extra/``);
4. ``settingsDir``: relative path to the folder containing the settings.properties.
5. ``alpha``: Amount of untranslatable strings
6. ``Output``: relative path of the folder where the test results will be stored;

Languagues can be selected or deselected editing the ``settings.properties`` file. To deselect a language, either comment (#) or delete the corresponding line.
### Example
```
cd ITDroid
java -jar target/MutAPK-1.0.0.jar foo.apk or.foo.app ./extra/ ./ 2 ./results/
```

### Output
The output directory will contain the results from the excuted tests and the intermediate steps
