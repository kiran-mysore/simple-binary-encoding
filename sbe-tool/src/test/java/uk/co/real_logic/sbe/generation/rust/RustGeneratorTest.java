package uk.co.real_logic.sbe.generation.rust;

import junit.framework.AssertionFailedError;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import uk.co.real_logic.sbe.TestUtil;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.xml.IrGenerator;
import uk.co.real_logic.sbe.xml.MessageSchema;
import uk.co.real_logic.sbe.xml.ParserOptions;

import java.io.*;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static uk.co.real_logic.sbe.generation.rust.RustTest.minimalDummyIr;
import static uk.co.real_logic.sbe.xml.XmlSchemaParser.parse;

public class RustGeneratorTest
{
    private static final String BROAD_USE_CASES_SCHEMA = "code-generation-schema";
    private static final String BASIC_TYPES_SCHEMA = "basic-types-schema";
    private static final String NESTED_GROUP_SCHEMA = "nested-group-schema";
    private SingleStringOutputManager outputManager;

    @Rule
    public final TemporaryFolder folderRule = new TemporaryFolder();

    @Before
    public void setUp()
    {
        outputManager = new SingleStringOutputManager();
    }

    private static Ir generateIrForResource(final String localResourceName)
    {
        final ParserOptions options = ParserOptions.builder().stopOnError(true).build();
        final String xmlLocalResourceName = localResourceName.endsWith(".xml") ?
            localResourceName : localResourceName + ".xml";
        final MessageSchema schema;

        try
        {
            schema = parse(TestUtil.getLocalResource(xmlLocalResourceName), options);
        }
        catch (final Exception e)
        {
            throw new IllegalStateException(e);
        }

        final IrGenerator irg = new IrGenerator();

        return irg.generate(schema);
    }

    @Test(expected = NullPointerException.class)
    public void nullOutputManagerTossesNpe()
    {
        new RustGenerator(minimalDummyIr(), null);
    }

    @Test(expected = NullPointerException.class)
    public void nullIrTossesNpe()
    {
        new RustGenerator(null, outputManager);
    }

    @Test
    public void generateSharedImports() throws IOException
    {
        RustGenerator.generateSharedImports(outputManager);
        assertContainsSharedImports(outputManager.toString());
    }

    private static void assertContainsSharedImports(final String generatedRust)
    {
        assertTrue(generatedRust.contains("Imports core rather than std"));
        assertTrue(generatedRust.contains("extern crate core;"));
    }

    @Test
    public void generateBasicEnum() throws IOException
    {
        RustGenerator.generateSharedImports(outputManager);
        assertContainsSharedImports(outputManager.toString());
    }

    private static String fullGenerateForResource(
        final SingleStringOutputManager outputManager, final String localResourceName)
    {
        outputManager.clear();

        final RustGenerator rustGenerator = new RustGenerator(generateIrForResource(localResourceName), outputManager);
        try
        {
            rustGenerator.generate();
        }
        catch (final IOException e)
        {
            throw new AssertionFailedError("Unexpected IOException during generation. " + e.getMessage());
        }

        return outputManager.toString();
    }

    @Test
    public void fullGenerateBasicTypes()
    {
        final String generatedRust = fullGenerateForResource(outputManager, BASIC_TYPES_SCHEMA);
        assertContainsSharedImports(generatedRust);
        assertContainsNumericEnum(generatedRust);
    }

    private void assertContainsNumericEnum(final String generatedRust)
    {
        final String expectedDeclaration =
            "#[derive(Clone,Copy,Debug,PartialEq,Eq,PartialOrd,Ord,Hash)]\n" +
            "#[repr(u8)]\n" +
            "pub enum ENUM {\n" +
            "  Value1 = 1u8,\n" +
            "  Value10 = 10u8,\n" +
            "  NullVal = 255u8,\n" +
            "}\n";
        assertTrue(generatedRust.contains(expectedDeclaration));
    }

    @Test
    public void fullGenerateBroadUseCase() throws IOException, InterruptedException
    {
        final String generatedRust = fullGenerateForResource(outputManager, "example-schema");
        assertContainsSharedImports(generatedRust);
        assertContains(generatedRust,
            "pub fn car_fields(mut self) -> CodecResult<(&'d CarFields, CarFuelFiguresHeaderDecoder<'d>)> {\n" +
            "    let v = self.scratch.read_type::<CarFields>(49)?;\n" +
            "    Ok((v, CarFuelFiguresHeaderDecoder::wrap(self.scratch)))\n" +
            "  }");

        final String expectedBooleanTypeDeclaration =
            "#[derive(Clone,Copy,Debug,PartialEq,Eq,PartialOrd,Ord,Hash)]\n" +
            "#[repr(u8)]\n" +
            "pub enum BooleanType {\n" +
            "  F = 0u8,\n" +
            "  T = 1u8,\n" +
            "  NullVal = 255u8,\n" +
            "}\n";
        assertTrue(generatedRust.contains(expectedBooleanTypeDeclaration));

        final String expectedCharTypeDeclaration =
            "#[derive(Clone,Copy,Debug,PartialEq,Eq,PartialOrd,Ord,Hash)]\n" +
            "#[repr(i8)]\n" +
            "pub enum Model {\n" +
            "  A = 65i8,\n" +
            "  B = 66i8,\n" +
            "  C = 67i8,\n" +
            "  NullVal = 0i8,\n" +
            "}\n";
        assertTrue(generatedRust.contains(expectedCharTypeDeclaration));
        assertRustBuildable(generatedRust, Optional.of("example-schema"));
    }

    private File writeCargoFolderWrapper(final String name, final String generatedRust, final File folder)
        throws IOException
    {
        final File src = new File(folder, "src");
        assertTrue(src.mkdirs());

        final File cargo = new File(folder, "Cargo.toml");
        try (Writer cargoWriter = Files.newBufferedWriter(cargo.toPath()))
        {
            cargoWriter.append("[package]\n")
                .append(String.format("name = \"%s\"\n", name))
                .append("version = \"0.1.0\"\n")
                .append("authors = []\n\n")
                .append("[dependencies]\n")
                .append("[dev-dependencies]\n");
        }

        final File lib = new File(src, "lib.rs");
        try (Writer libWriter = Files.newBufferedWriter(lib.toPath()))
        {
            libWriter.append(generatedRust);
        }

        return folder;
    }

    private static final class CargoCheckResult
    {
        final boolean isSuccess;
        final String error;

        private CargoCheckResult(final boolean isSuccess, final String error)
        {
            this.isSuccess = isSuccess;
            this.error = error;
        }
    }

    private static CargoCheckResult cargoCheckInDirectory(final File folder) throws IOException, InterruptedException
    {
        final ProcessBuilder builder = new ProcessBuilder("cargo", "check");
        builder.directory(folder);
        final Process process = builder.start();
        process.waitFor(30, TimeUnit.SECONDS);
        final boolean success = process.exitValue() == 0;

        final StringBuilder errorString = new StringBuilder();
        if (!success)
        {
            // Include output as a debugging aid when things go wrong
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream())))
            {
                while (true)
                {
                    final String line = errorReader.readLine();
                    if (line == null)
                    {
                        break;
                    }
                    else
                    {
                        errorString.append(line);
                        errorString.append('\n');
                    }
                }
            }
        }

        return new CargoCheckResult(success, errorString.toString());
    }

    private static boolean cargoExists()
    {
        try
        {
            final ProcessBuilder builder = new ProcessBuilder("cargo", "-V");
            final Process process = builder.start();
            process.waitFor(5, TimeUnit.SECONDS);
            return process.exitValue() == 0;
        }
        catch (final IOException | InterruptedException | IllegalThreadStateException ignore)
        {
            return false;
        }
    }

    private void assertRustBuildable(final String generatedRust, final Optional<String> name)
        throws IOException, InterruptedException
    {
        Assume.assumeTrue(cargoExists());
        final File folder = writeCargoFolderWrapper(name.orElse("test"), generatedRust, folderRule.newFolder());
        final CargoCheckResult result = cargoCheckInDirectory(folder);
        assertTrue(String.format("Generated Rust (%s) should be buildable with cargo", name) + result.error,
            result.isSuccess);
    }

    private void assertSchemaInterpretableAsRust(final String localResourceSchema)
        throws IOException, InterruptedException
    {
        final String rust = fullGenerateForResource(outputManager, localResourceSchema);
        assertRustBuildable(rust, Optional.of(localResourceSchema));
    }

    @Test
    public void checkValidRustFromAllExampleSchema() throws IOException, InterruptedException
    {
        final String[] schema =
        {
            "basic-group-schema",
            BASIC_TYPES_SCHEMA,
            "basic-variable-length-schema",
            "block-length-schema",
            BROAD_USE_CASES_SCHEMA,
            "composite-elements-schema",
            "composite-elements-schema-rc4",
            "composite-offsets-schema",
            "encoding-types-schema",
            "example-schema",
            "FixBinary",
            "group-with-data-schema",
            "group-with-constant-fields",
            "issue435",
            "message-block-length-test",
            NESTED_GROUP_SCHEMA,
            "new-order-single-schema",
        };

        for (final String s : schema)
        {
            assertSchemaInterpretableAsRust(s);
        }
    }

    @Test
    public void messageWithOffsets()
    {
        final String rust = fullGenerateForResource(outputManager, "composite-offsets-schema");
        final String expectedHeader =
            "pub struct MessageHeader {\n" +
            "  pub block_length:u16,\n" +
            "  template_id_padding_1:[u8;2],\n" +
            "  pub template_id:u16,\n" +
            "  schema_id_padding_1:[u8;2],\n" +
            "  pub schema_id:u16,\n" +
            "  pub version:u16,\n" +
            "}";
        assertContains(rust, expectedHeader);
    }

    @Test
    public void messageBlockLengthExceedingSumOfFieldLengths()
    {
        final String rust = fullGenerateForResource(outputManager, "message-block-length-test");
        final String expectedEncoderSegment = "let v = self.scratch.writable_overlay::<MsgNameFields>(9+2)?;";
        assertContains(rust, expectedEncoderSegment);
        final String expectedDecoderSegment = "let v = self.scratch.read_type::<MsgNameFields>(9)?;\n" +
            "    self.scratch.skip_bytes(2)?;";
        assertContains(rust, expectedDecoderSegment);
    }

    @Test
    public void constantEnumFields() throws IOException, InterruptedException
    {
        final String rust = fullGenerateForResource(outputManager, "constant-enum-fields");
        assertContainsSharedImports(rust);
        final String expectedCharTypeDeclaration =
            "#[derive(Clone,Copy,Debug,PartialEq,Eq,PartialOrd,Ord,Hash)]\n" +
            "#[repr(i8)]\n" +
            "pub enum Model {\n" +
            "  A = 65i8,\n" +
            "  B = 66i8,\n" +
            "  C = 67i8,\n" +
            "  NullVal = 0i8,\n" +
            "}\n";
        assertContains(rust, expectedCharTypeDeclaration);
        assertContains(rust, "pub struct ConstantEnumsFields {\n}");
        assertContains(rust, "impl ConstantEnumsFields {");
        assertContains(rust, "  pub fn c() -> Model {\n" +
            "    Model::C\n  }");
        assertContains(rust, "impl ConstantEnumsFMember {");
        assertContains(rust, "  pub fn k() -> Model {\n" +
            "    Model::C\n  }");
        assertContains(rust,
            "pub fn constant_enums_fields(mut self) -> " +
            "CodecResult<(&'d ConstantEnumsFields, ConstantEnumsFHeaderDecoder<'d>)> {\n" +
            "    let v = self.scratch.read_type::<ConstantEnumsFields>(0)?;\n" +
            "    Ok((v, ConstantEnumsFHeaderDecoder::wrap(self.scratch)))\n" +
            "  }");
        assertRustBuildable(rust, Optional.of("constant-enum-fields"));
    }

    @Test
    public void constantFieldsCase() throws IOException, InterruptedException
    {
        final String rust = fullGenerateForResource(outputManager, "group-with-constant-fields");
        assertContainsSharedImports(rust);
        final String expectedCharTypeDeclaration =
            "#[derive(Clone,Copy,Debug,PartialEq,Eq,PartialOrd,Ord,Hash)]\n" +
            "#[repr(i8)]\n" +
            "pub enum Model {\n" +
            "  A = 65i8,\n" +
            "  B = 66i8,\n" +
            "  C = 67i8,\n" +
            "  NullVal = 0i8,\n" +
            "}\n";
        assertContains(rust, expectedCharTypeDeclaration);
        final String expectedComposite =
            "pub struct CompositeWithConst {\n" +
            "  pub w:u8,\n" +
            "}";
        assertContains(rust, expectedComposite);
        assertContains(rust, "impl CompositeWithConst {");
        assertContains(rust, "  pub fn x() -> u8 {\n" +
            "    250u8\n  }");
        assertContains(rust, "  pub fn y() -> u16 {\n" +
            "    9000u16\n  }");
        assertContains(rust, "pub struct ConstantsGaloreFields {\n" +
            "  pub a:u8,\n" +
            "  pub e:CompositeWithConst,\n}");
        assertContains(rust, "impl ConstantsGaloreFields {");
        assertContains(rust, "  pub fn b() -> u16 {\n" +
            "    9000u16\n  }");
        assertContains(rust, "  pub fn c() -> Model {\n" +
            "    Model::C\n  }");
        assertContains(rust, "  pub fn d() -> u16 {\n" +
            "    9000u16\n  }");
        assertContains(rust, "pub struct ConstantsGaloreFMember {\n" +
            "  pub g:u8,\n" +
            "  pub h:CompositeWithConst,\n}");
        assertContains(rust, "impl ConstantsGaloreFMember {");
        assertContains(rust, "  pub fn i() -> u16 {\n" +
            "    9000u16\n  }");
        assertContains(rust, "  pub fn j() -> u16 {\n" +
            "    9000u16\n  }");
        assertContains(rust, "  pub fn k() -> Model {\n" +
            "    Model::C\n  }");
        assertContains(rust, "  pub fn l() -> &'static str {\n" +
            "    \"Huzzah\"\n  }");
        assertRustBuildable(rust, Optional.of("group-with-constant-fields"));
    }

    private static void assertContains(final String haystack, final String needle)
    {
        assertThat(haystack, containsString(needle));
    }
}
